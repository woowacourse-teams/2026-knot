import { parseWhisperOutput } from '../src/main/pipeline/whisper'
import type { SttSegment, SttWord } from '../src/core/types'
import { assignSpeakers, mergeUtterances, SERVER_SPEAKER_POLICY } from '../src/core/merge'

export interface WindowInput {
  pipelineVersion: string
  inputManifestSha256: string
  inputStartFrame: number
  inputEndFrame: number
  /** 생략하면 DEFAULT_ONLY. 녹음의 마지막 구간은 종료 후 대기를 줄이려고 기본 디코딩만 쓴다. */
  decodePolicy?: DecodePolicy
}
export const DECODE_POLICIES = ['DEFAULT_ONLY', 'WITH_VOCABULARY'] as const
export type DecodePolicy = typeof DECODE_POLICIES[number]
/** 새 음성 2분과 직전 10초 */
export const MAXIMUM_WINDOW_FRAMES = 130 * 16000
interface Evidence {
  modelVersion: string
  speakers: {speakerId:string; embedding:number[]; voicedFrames:number}[]
  segments: {start:number; end:number; speaker:string}[]
  /** BE가 녹음 종료 시 다시 묶는 5초 이하 화자 근거 조각 */
  observations?: {start:number; end:number; speaker:string; embedding:number[]}[]
}
/** float32로 계산한 특징값은 유효 숫자 9자리로 그대로 복원되므로 결과 크기를 줄이려고 그만큼만 보낸다. */
const float32Digits = (value:number) => Number(value.toPrecision(9))
export const validWindow = (w:WindowInput) => typeof w.pipelineVersion === 'string'
  && w.pipelineVersion.trim().length > 0 && w.pipelineVersion.length <= 64
  && /^[0-9a-f]{64}$/.test(w.inputManifestSha256)
  && Number.isSafeInteger(w.inputStartFrame) && Number.isSafeInteger(w.inputEndFrame)
  && w.inputStartFrame >= 0 && w.inputEndFrame > w.inputStartFrame && w.inputEndFrame <= 115200000
  && w.inputEndFrame-w.inputStartFrame <= MAXIMUM_WINDOW_FRAMES
  && (w.decodePolicy === undefined || DECODE_POLICIES.includes(w.decodePolicy))

const combineWords = (first: SttWord, second: SttWord): SttWord => ({
  start: Math.min(first.start, second.start),
  end: Math.max(first.end, second.end),
  text: `${first.text} ${second.text}`
})

export const measuredWordGroups = (words: SttWord[], frames = Number.MAX_SAFE_INTEGER): SttWord[] => {
  const groups: SttWord[] = []
  let leading: SttWord | undefined
  for (const word of words) {
    if (!Number.isFinite(word.start) || !Number.isFinite(word.end) || word.start < 0 || word.end < word.start)
      throw new Error('INVALID_RESULT')
    const rawStart = Math.round(word.start * 16000), rawEnd = Math.round(word.end * 16000)
    if (!Number.isSafeInteger(rawStart) || !Number.isSafeInteger(rawEnd)) throw new Error('INVALID_RESULT')
    // A point at frame zero belongs to the input; a point at its exclusive end does not.
    if (rawStart >= frames || (rawEnd <= 0 && rawStart !== rawEnd)) continue
    if (rawStart === rawEnd) {
      const previous = groups.at(-1)
      if (previous) {
        groups[groups.length - 1] = combineWords(previous, word)
        continue
      }
      leading = leading ? combineWords(leading, word) : word
      continue
    }
    groups.push(leading ? combineWords(leading, word) : word)
    leading = undefined
  }
  // A segment without a positive-duration word supplies no measured anchor for its text.
  if (leading) throw new Error('INVALID_RESULT')
  return groups
}

/** decoded는 raw를 고른 뒤 용어 융합까지 마친 결과다. raw 원문은 그대로 보존한다. */
export const assembleWindowResult = (raw:Buffer, evidence:Evidence, window:WindowInput,
    decoded:SttSegment[] = parseWhisperOutput(raw)) => {
  const frames = window.inputEndFrame-window.inputStartFrame
  if (!validWindow(window) || !evidence.modelVersion || !Array.isArray(evidence.speakers)
      || !Array.isArray(evidence.segments) || evidence.speakers.length > 64) throw new Error('INVALID_RESULT')
  const ids = new Set<string>()
  const speakers = evidence.speakers.map(s => ({...s}))
  for (const s of speakers) {
    if (!s.speakerId || ids.has(s.speakerId) || !Array.isArray(s.embedding)
        || (s.embedding.length !== 0 && s.embedding.length !== 512)
        || s.embedding.some(x => !Number.isFinite(x)) || !Number.isSafeInteger(s.voicedFrames)
        || s.voicedFrames < 0 || s.voicedFrames > frames) throw new Error('INVALID_RESULT')
    ids.add(s.speakerId)
  }
  for (const s of evidence.segments) {
    if (!Number.isFinite(s.start) || !Number.isFinite(s.end) || s.start < 0 || s.end <= s.start
        || Math.round(s.end*16000)>frames || !ids.has(s.speaker)) throw new Error('INVALID_RESULT')
  }
  const observations = (evidence.observations ?? []).map(o => {
    const startFrame = Math.round(o.start*16000), endFrame = Math.round(o.end*16000)
    if (!Number.isFinite(o.start) || !Number.isFinite(o.end) || startFrame < 0 || endFrame <= startFrame
        || endFrame > frames || !ids.has(o.speaker) || !Array.isArray(o.embedding) || o.embedding.length !== 512
        || o.embedding.some(x => !Number.isFinite(x))) throw new Error('INVALID_RESULT')
    return {startFrame, endFrame, speakerId: o.speaker, embedding: o.embedding.map(float32Digits)}
  })
  const segments = decoded.map(s => {
    if (s.text.trim() && !s.words?.length) throw new Error('WORD_TIMESTAMPS_REQUIRED')
    return {...s, words: measuredWordGroups(s.words??[], frames).map(w => ({
      ...w, start: Math.round(w.start*16000)/16000,
      end: Math.min(frames, Math.round(w.end*16000))/16000
    }))}
  }).filter(s => s.words.length > 0)
  const pieces = assignSpeakers({segments, speakerSegments: evidence.segments, ...SERVER_SPEAKER_POLICY})
  // BE가 전역 화자로 다시 배정할 때 문장 경계를 같은 규칙으로 정하도록 Whisper segment 번호를 남긴다.
  const segmentOf = segments.flatMap((s, index) => s.words.map(() => index))
  const tokens = pieces.map((piece, index) => {
    const speakerId = piece.speaker
    if (!ids.has(speakerId)) {
      ids.add(speakerId)
      speakers.push({speakerId, embedding: [], voicedFrames: 0})
    }
    return {startFrame: Math.round(piece.start*16000), endFrame: Math.round(piece.end*16000),
      text: piece.text, speakerId, segment: segmentOf[index]}
  }).sort((a,b)=>a.startFrame-b.startFrame||a.endFrame-b.endFrame)
  const utterances = mergeUtterances(pieces, SERVER_SPEAKER_POLICY)
  const {decodePolicy, ...request} = window
  return {schemaVersion:1,rawWhisperJson:raw.toString('utf8'),utterances,
    window:{schemaVersion:1,...request,modelVersion:evidence.modelVersion,tokens,speakers,observations}}
}
