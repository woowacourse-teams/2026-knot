import { spawn, type ChildProcess } from 'node:child_process'
import { access, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { assembleWindowResult, measuredWordGroups, validWindow, type WindowInput } from './windowResult'
import { assignSpeakers, mergeUtterances, SERVER_SPEAKER_POLICY } from '../src/core/merge'
import type { SttWord } from '../src/core/types'
import { buildDiarizeArgs, parseDiarizeOutput } from '../src/main/pipeline/diarize'
import { normalizeWavFile } from '../src/main/pipeline/normalize'
import { assertRestoredVadTimestamps, buildWhisperArgs, parseWhisperOutput } from '../src/main/pipeline/whisper'

export interface ServerManifest {
  audioPath: string
  outputDirectory: string
  speakerCount?: number
  window?: WindowInput
  profile: {
    sherpaLibrary?: string
    pythonBinary?: string
    whisperBinary: string
    diarizeBinary: string
    whisperModel: string
    whisperVadEnabled?: boolean
    vadModel: string
    segmentationModel: string
    embeddingModel: string
    sttThreads: number
    diarizeThreads: number
  }
}

const positiveInteger = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) > 0

export const validateManifest = (value: unknown): ServerManifest => {
  const manifest = value as ServerManifest
  if (
    !manifest ||
    !manifest.profile ||
    (manifest.profile.whisperVadEnabled !== undefined && typeof manifest.profile.whisperVadEnabled !== 'boolean') ||
    (manifest.speakerCount !== undefined && !positiveInteger(manifest.speakerCount)) ||
    !positiveInteger(manifest.profile.sttThreads) ||
    !positiveInteger(manifest.profile.diarizeThreads)
  )
    throw new Error('INVALID_MANIFEST')
  if (manifest.window && (!validWindow(manifest.window)
      || typeof manifest.profile.sherpaLibrary !== 'string' || !path.isAbsolute(manifest.profile.sherpaLibrary)
      || typeof manifest.profile.pythonBinary !== 'string' || !path.isAbsolute(manifest.profile.pythonBinary)))
    throw new Error('INVALID_MANIFEST')
  const paths = [
    manifest.audioPath,
    manifest.outputDirectory,
    manifest.profile.whisperBinary,
    manifest.profile.diarizeBinary,
    manifest.profile.whisperModel,
    manifest.profile.vadModel,
    manifest.profile.segmentationModel,
    manifest.profile.embeddingModel
  ]
  if (paths.some((item) => typeof item !== 'string' || !path.isAbsolute(item))) {
    throw new Error('INVALID_MANIFEST')
  }
  if (path.dirname(manifest.audioPath) !== manifest.outputDirectory)
    throw new Error('INVALID_MANIFEST')
  return manifest
}

/** 원음 길이를 가진 단어가 없는 전체 전사 segment는 단어 없이 segment 시각으로 화자를 배정한다. */
const hasMeasuredWord = (words: SttWord[]) =>
  words.some(word => Math.round(word.end * 16000) > Math.round(word.start * 16000))

export const assembleResult = (raw: Buffer, diarization: string, speakerCount?: number) => {
  const segments = parseWhisperOutput(raw).map(segment => segment.words && hasMeasuredWord(segment.words)
    ? { ...segment, words: measuredWordGroups(segment.words) } : { ...segment, words: [] })
  const speakerSegments = parseDiarizeOutput(diarization)
  if (segments.some((item) => item.text.trim()) && !speakerSegments.length) {
    throw new Error('DIARIZATION_EMPTY')
  }
  const utterances = mergeUtterances(
    assignSpeakers({
      segments,
      speakerSegments,
      ...SERVER_SPEAKER_POLICY
    }),
    SERVER_SPEAKER_POLICY
  )
  if (
    utterances.some(
      (item, index) =>
        item.ord !== index ||
        !Number.isFinite(item.startSec) ||
        !Number.isFinite(item.endSec) ||
        item.startSec < 0 ||
        item.endSec < item.startSec ||
        typeof item.text !== 'string' ||
        typeof item.speakerLabel !== 'string'
    )
  ) {
    throw new Error('INVALID_RESULT')
  }
  return { schemaVersion: 1 as const, rawWhisperJson: raw.toString('utf8'), utterances }
}

/** No native output reaches logs: diarization stdout is only parsed in memory. */
export const runServerPipeline = async (manifest: ServerManifest) => {
  const children = new Set<ChildProcess>()
  let aborted = false
  let abortCode = 'CANCELLED'
  const stop = (code: string) => {
    aborted = true
    abortCode = code
    for (const child of children) {
      child.kill('SIGTERM')
      const escalation = setTimeout(() => child.kill('SIGKILL'), 750)
      escalation.unref()
      child.once('close', () => clearTimeout(escalation))
    }
  }
  const signal = () => stop('CANCELLED')
  process.on('SIGTERM', signal)
  process.on('SIGINT', signal)
  const run = (
    command: string,
    args: string[],
    capture: boolean,
    failureCode: 'STT_FAILED' | 'DIARIZATION_FAILED'
  ) =>
    new Promise<string>((resolve, reject) => {
      if (aborted) {
        reject(new Error(abortCode))
        return
      }
      const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'ignore'], shell: false })
      children.add(child)
      const chunks: Buffer[] = []
      let size = 0
      child.stdout?.on('data', (chunk: Buffer) => {
        if (!capture) return
        size += chunk.length
        if (size > 32 * 1024 * 1024) {
          stop('OUTPUT_LIMIT')
          return
        }
        chunks.push(chunk)
      })
      child.once('error', () => {
        children.delete(child)
        stop(failureCode)
        reject(new Error(failureCode))
      })
      child.once('close', (code) => {
        children.delete(child)
        if (aborted || code !== 0) {
          const failure = aborted ? abortCode : failureCode
          stop(failure)
          reject(new Error(failure))
        } else resolve(Buffer.concat(chunks).toString('utf8'))
      })
    })
  try {
    await Promise.all(
      [
        manifest.audioPath,
        ...[
          manifest.profile.whisperBinary,
          manifest.profile.diarizeBinary,
          manifest.profile.whisperModel,
          manifest.profile.vadModel,
          manifest.profile.segmentationModel,
          manifest.profile.embeddingModel
        ]
      ].map((file) => access(file))
    )
    await mkdir(manifest.outputDirectory, { recursive: true })
    const prefix = path.join(manifest.outputDirectory, 'whisper')
    const normalized = path.join(manifest.outputDirectory, 'normalized.wav')
    await rm(path.join(manifest.outputDirectory, 'result.json'), { force: true })
    await normalizeWavFile({ inputPath: manifest.audioPath, outputPath: normalized })
    if (aborted) throw new Error(abortCode)
    const p = manifest.profile
    const stt = () =>
      run(
        p.whisperBinary,
        buildWhisperArgs({
          modelPath: p.whisperModel,
          audioPath: normalized,
          outputPath: prefix,
          threads: p.sttThreads,
          vadEnabled: p.whisperVadEnabled ?? true,
          vadModelPath: p.vadModel
        }),
        false,
        'STT_FAILED'
      )
    const evidencePath = path.join(manifest.outputDirectory, 'window-speakers.json')
    const diarize = () => manifest.window ? run(
      p.pythonBinary!, [path.join(path.dirname(new URL(import.meta.url).pathname), 'windowDiarization.py'),
        path.join(manifest.outputDirectory, 'window-input.json'), evidencePath], false, 'DIARIZATION_FAILED'
    ) : run(
        p.diarizeBinary,
        buildDiarizeArgs({
          segmentationModelPath: p.segmentationModel,
          embeddingModelPath: p.embeddingModel,
          audioPath: normalized,
          threads: p.diarizeThreads,
          speakerCount: manifest.speakerCount
        }),
        true,
        'DIARIZATION_FAILED'
      )
    if (manifest.window) {
      await writeFile(path.join(manifest.outputDirectory, 'window-input.json'),
        JSON.stringify({...manifest, audioPath: normalized}), {mode:0o600})
    }
    const [, diarization] = await Promise.all([stt(), diarize()])
    const raw = await readFile(`${prefix}.json`)
    if (p.whisperVadEnabled ?? true) assertRestoredVadTimestamps(raw)
    const result = manifest.window ? assembleWindowResult(raw,
      JSON.parse(await readFile(evidencePath,'utf8')), manifest.window)
      : assembleResult(raw, diarization, manifest.speakerCount)
    if (manifest.window && Buffer.byteLength(JSON.stringify(result),'utf8') > 1048576)
      throw new Error('OUTPUT_LIMIT')
    if (aborted) throw new Error(abortCode)
    const temporary = path.join(manifest.outputDirectory, 'result.json.tmp')
    await writeFile(temporary, JSON.stringify(result), { encoding: 'utf8', mode: 0o600 })
    await rename(temporary, path.join(manifest.outputDirectory, 'result.json'))
  } finally {
    process.off('SIGTERM', signal)
    process.off('SIGINT', signal)
    for (const child of children) child.kill('SIGKILL')
  }
}

const main = async () => {
  const args = process.argv.slice(2)
  if (args.length !== 2 || args[0] !== '--manifest') throw new Error('INVALID_ARGUMENTS')
  const manifest = validateManifest(JSON.parse(await readFile(args[1], 'utf8')))
  try {
    await runServerPipeline(manifest)
  } catch (error) {
    const code =
      error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'RUNNER_FAILED'
    await writeFile(
      path.join(manifest.outputDirectory, 'diagnostic.json'),
      JSON.stringify({ code }),
      { mode: 0o600 }
    )
    throw new Error(code)
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => {
    process.stderr.write('RUNNER_FAILED\n')
    process.exitCode = 1
  })
}
