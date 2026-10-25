import assert from 'node:assert/strict'
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { test } from 'node:test'
import { buildWhisperArgs } from '../src/main/pipeline/whisper'
import { runServerPipeline } from './serverPipeline'
import { MAXIMUM_WINDOW_FRAMES, validWindow, type DecodePolicy } from './windowResult'

test('a window carries up to 130 seconds and one of the two decode policies', () => {
  // given
  const window = { pipelineVersion: 'pcm-v1', inputManifestSha256: 'a'.repeat(64), inputStartFrame: 16000,
    inputEndFrame: 16000 + MAXIMUM_WINDOW_FRAMES }

  // when
  const valid = [window, { ...window, inputEndFrame: window.inputEndFrame + 1 },
    { ...window, decodePolicy: 'WITH_VOCABULARY' as const }, { ...window, decodePolicy: 'TWICE' as never }]
    .map(validWindow)

  // then
  assert.deepEqual(valid, [true, false, true, false])
})

test('vocabulary and retry decodes add only their prompt or context option', () => {
  // given
  const base = { modelPath: '/m.bin', audioPath: '/in.wav', outputPath: '/out', threads: 4, vadEnabled: false }

  // when
  const args = [buildWhisperArgs({ ...base, prompt: '회의 용어' }), buildWhisperArgs({ ...base, maxContext: 0 })]

  // then
  assert.deepEqual(args.map((item) => item.slice(12)), [['--prompt', '회의 용어'], ['-mc', '0']])
})

const fakeWhisper = `#!/usr/bin/env python3
import json,os,sys
here=os.path.dirname(os.path.abspath(__file__))
mode=json.load(open(os.path.join(here,'mode.json')))
open(os.path.join(here,'calls.jsonl'),'a').write(json.dumps(sys.argv[1:])+'\\n')
kind='vocabulary' if '--prompt' in sys.argv else 'retry' if '-mc' in sys.argv else 'default'
if mode.get('fail')==kind: sys.exit(3)
rest=['다음','안건은','일정','정리입니다']
texts={'default':mode['default'],'retry':['오늘','하내스','설정을','봐요']+rest,'vocabulary':['오늘','하네스','설정을','봐요']+rest}[kind]
tokens=[{'text':' '+text,'offsets':{'from':i*1000,'to':i*1000+900}} for i,text in enumerate(texts)]
segment={'offsets':{'from':0,'to':len(texts)*1000},'text':' '.join(texts),'tokens':tokens}
open(sys.argv[sys.argv.index('-of')+1]+'.json','w').write(json.dumps({'transcription':[segment]},ensure_ascii=False))
`
const fakeDiarization = `#!/usr/bin/env python3
import json,sys
json.dump({'modelVersion':'fake','speakers':[{'speakerId':'speaker_0','embedding':[],'voicedFrames':16000}],
  'segments':[{'start':0,'end':60,'speaker':'speaker_0'}]},open(sys.argv[3],'w'))
`
const silentWav = () => {
  const buffer = Buffer.alloc(44 + 32000)
  buffer.write('RIFF', 0)
  buffer.writeUInt32LE(36 + 32000, 4)
  buffer.write('WAVEfmt ', 8)
  buffer.writeUInt32LE(16, 16)
  buffer.writeUInt16LE(1, 20)
  buffer.writeUInt16LE(1, 22)
  buffer.writeUInt32LE(16000, 24)
  buffer.writeUInt32LE(32000, 28)
  buffer.writeUInt16LE(2, 32)
  buffer.writeUInt16LE(16, 34)
  buffer.write('data', 36)
  buffer.writeUInt32LE(32000, 40)
  return buffer
}

interface DecodeRun {
  decodePolicy: DecodePolicy
  default: string[]
  fail?: 'retry' | 'vocabulary'
}

/** 가짜 whisper·화자 분리로 구간 하나를 실행하고 최종 텍스트와 디코딩 종류 순서를 돌려준다. */
const runWindow = async (run: DecodeRun) => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'window-decode-'))
  try {
    const whisper = path.join(directory, 'whisper')
    const python = path.join(directory, 'python')
    await writeFile(whisper, fakeWhisper)
    await writeFile(python, fakeDiarization)
    await chmod(whisper, 0o700)
    await chmod(python, 0o700)
    await writeFile(path.join(directory, 'mode.json'), JSON.stringify(run))
    const job = path.join(directory, 'job')
    await mkdir(job)
    const audioPath = path.join(job, 'audio.wav')
    await writeFile(audioPath, silentWav())
    await runServerPipeline({
      audioPath,
      outputDirectory: job,
      window: { pipelineVersion: 'pcm-v1', inputManifestSha256: 'a'.repeat(64), inputStartFrame: 0,
        inputEndFrame: 60 * 16000, decodePolicy: run.decodePolicy },
      profile: {
        sherpaLibrary: whisper,
        pythonBinary: python,
        whisperVadEnabled: false,
        whisperBinary: whisper,
        diarizeBinary: whisper,
        whisperModel: whisper,
        vadModel: whisper,
        segmentationModel: whisper,
        embeddingModel: whisper,
        sttThreads: 1,
        diarizeThreads: 1
      }
    })
    const result = JSON.parse(await readFile(path.join(job, 'result.json'), 'utf8'))
    const calls = (await readFile(path.join(directory, 'calls.jsonl'), 'utf8')).trim().split('\n')
      .map((line) => JSON.parse(line) as string[])
      .map((args) => args.includes('--prompt') ? 'vocabulary' : args.includes('-mc') ? 'retry' : 'default')
    return { text: result.utterances.map((utterance: { text: string }) => utterance.text).join(' '), calls,
      window: result.window }
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
}

const repeated = ['오늘', ...Array(8).fill('Q.')]
const rest = ' 다음 안건은 일정 정리입니다'

for (const scenario of [
  { name: 'a recording window fuses the vocabulary decode', run: { decodePolicy: 'WITH_VOCABULARY', default: ['오늘', '하내스', '설정을', '봐요'] },
    text: '오늘 하네스 설정을 봐요', calls: ['default', 'vocabulary'] },
  { name: 'the final window decodes once', run: { decodePolicy: 'DEFAULT_ONLY', default: ['오늘', '하내스', '설정을', '봐요'] },
    text: '오늘 하내스 설정을 봐요', calls: ['default'] },
  { name: 'a repeated default decode is replaced by one context-free retry', run: { decodePolicy: 'WITH_VOCABULARY', default: repeated },
    text: '오늘 하네스 설정을 봐요' + rest, calls: ['default', 'retry', 'vocabulary'] },
  { name: 'the final window still retries a repeated decode', run: { decodePolicy: 'DEFAULT_ONLY', default: repeated },
    text: '오늘 하내스 설정을 봐요' + rest, calls: ['default', 'retry'] },
  { name: 'a failed vocabulary decode keeps the default decode', run: { decodePolicy: 'WITH_VOCABULARY', default: ['오늘', '하내스', '설정을', '봐요'], fail: 'vocabulary' },
    text: '오늘 하내스 설정을 봐요', calls: ['default', 'vocabulary'] },
  { name: 'a failed retry keeps the repeated default decode', run: { decodePolicy: 'DEFAULT_ONLY', default: repeated, fail: 'retry' },
    text: repeated.join(' '), calls: ['default', 'retry'] }
] as { name: string; run: DecodeRun; text: string; calls: string[] }[]) {
  test(`window pipeline: ${scenario.name}`, async () => {
    // when
    const result = await runWindow(scenario.run)

    // then
    assert.equal(result.text, scenario.text)
    assert.deepEqual(result.calls, scenario.calls)
    assert.equal('decodePolicy' in result.window, false)
  })
}
