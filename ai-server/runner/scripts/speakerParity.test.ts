import assert from 'node:assert/strict'
import test from 'node:test'
import { assembleResult } from './serverPipeline'
import { assembleWindowResult } from './windowResult'
import type { SpeakerSegment } from '../src/core/types'

const windowInput = {
  pipelineVersion: 'speaker-parity',
  inputManifestSha256: 'a'.repeat(64),
  inputStartFrame: 0,
  inputEndFrame: 30 * 16000
}

interface Word { text: string; start: number; end: number }
const rawWords = (words: Word[]) => Buffer.from(JSON.stringify({ transcription: [{
  offsets: { from: words[0].start * 1000, to: words.at(-1)!.end * 1000 },
  text: words.map(word => word.text).join(' '),
  tokens: words.map(word => ({ text: ` ${word.text}`,
    offsets: { from: word.start * 1000, to: word.end * 1000 } }))
}] }))

for (const scenario of [
  {
    name: 'nearest speaker for a word outside measured diarization',
    words: [{ text: '맞습니다.', start: 1.2, end: 1.5 }],
    segments: [{ speaker: 'speaker_0', start: 0, end: 1 }],
    expected: ['speaker_0']
  },
  {
    name: 'previous speaker when the word is more than one second away',
    words: [{ text: '시작.', start: 0, end: 0.5 }, { text: '계속.', start: 4, end: 4.5 }],
    segments: [{ speaker: 'speaker_0', start: 0, end: 0.5 }],
    expected: ['speaker_0', 'speaker_0']
  },
  {
    name: 'sentence voting rather than separate overlap-only assignment',
    words: [{ text: '오늘', start: 0, end: 0.7 }, { text: '회의.', start: 0.7, end: 1 }],
    segments: [{ speaker: 'speaker_0', start: 0, end: 0.7 },
      { speaker: 'speaker_1', start: 0.7, end: 1 }],
    expected: ['speaker_0', 'speaker_0']
  },
  {
    name: 'a real brief speaker beside a speaker with ten seconds of audio',
    words: [{ text: '설명.', start: 0, end: 11 }, { text: '네.', start: 11, end: 11.2 }],
    segments: [{ speaker: 'speaker_0', start: 0, end: 11 },
      { speaker: 'speaker_1', start: 11, end: 11.2 }],
    expected: ['speaker_0', 'speaker_1']
  },
  {
    name: 'a sub-half-second response without orphan absorption',
    words: [{ text: '설명.', start: 0, end: 2 }, { text: '아니요.', start: 2, end: 2.2 }],
    segments: [{ speaker: 'speaker_0', start: 0, end: 2 },
      { speaker: 'speaker_1', start: 2, end: 2.2 }],
    expected: ['speaker_0', 'speaker_1']
  },
  {
    name: 'unknown speaker before any evidence and beyond nearest tolerance',
    words: [{ text: '먼저.', start: 0, end: 0.5 }, { text: '나중.', start: 5, end: 6 }],
    segments: [{ speaker: 'speaker_0', start: 5, end: 6 }],
    expected: ['UNKNOWN', 'speaker_0']
  }
]) {
  test(`full/window speaker parity: ${scenario.name}`, () => {
    // given
    const raw = rawWords(scenario.words)
    const segments: SpeakerSegment[] = scenario.segments
    const diarization = segments.map(segment => `${segment.start} -- ${segment.end} ${segment.speaker}`).join('\n')
    const evidence = {
      modelVersion: 'same-model',
      segments,
      speakers: [...new Set(segments.map(segment => segment.speaker))].map(speakerId => ({
        speakerId, embedding: Array(512).fill(0.1), voicedFrames: 16000
      }))
    }

    // when
    const result = { full: assembleResult(raw, diarization),
      window: assembleWindowResult(raw, evidence, windowInput) }

    // then
    assert.deepEqual(result.window.window.tokens.map(token => token.speakerId), scenario.expected)
    assert.deepEqual(result.window.utterances, result.full.utterances)
    assert.deepEqual(result.window.window.tokens.map(token => token.text), scenario.words.map(word => word.text))
  })
}

for (const words of [
  [{ text: '첫', start: 0, end: 0 }, { text: '발언.', start: 0, end: 1 }],
  [{ text: '첫', start: 0, end: 0.5 }, { text: '발언.', start: 0.5, end: 0.5 }],
  [{ text: '첫', start: 0, end: 0.5 }, { text: '발언', start: 0.5, end: 0.5 },
    { text: '마침.', start: 0.5, end: 1 }]
]) {
  test(`full/window speaker parity preserves measured groups: ${JSON.stringify(words)}`, () => {
    // given
    const raw = rawWords(words)
    const evidence = { modelVersion: 'same-model',
      speakers: [{ speakerId: 'speaker_0', embedding: Array(512).fill(0.1), voicedFrames: 16000 }],
      segments: [{ start: 0, end: 1, speaker: 'speaker_0' }] }

    // when
    const result = { full: assembleResult(raw, '0 -- 1 speaker_0'),
      window: assembleWindowResult(raw, evidence, windowInput) }

    // then
    assert.deepEqual(result.window.utterances, result.full.utterances)
    assert.equal(result.full.utterances.map(utterance => utterance.text).join(' '),
      words.map(word => word.text).join(' '))
    assert.ok(result.window.window.tokens.every(token => token.endFrame > token.startFrame))
  })
}

test('whole legacy segments without word timestamps still use segment-level assignment', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [
    { offsets: { from: 0, to: 1000 }, text: '단어 시각 없는 기존 결과.' }
  ] }))

  // when
  const result = assembleResult(raw, '0 -- 1 speaker_0')

  // then
  assert.deepEqual(result.utterances, [{ ord: 0, speakerLabel: 'speaker_0',
    startSec: 0, endSec: 1, text: '단어 시각 없는 기존 결과.' }])
})

test('whole segments with only zero-duration words fall back to segment-level assignment', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [
    { offsets: { from: 0, to: 1000 }, text: ' 네 맞아요.',
      tokens: [{ text: ' 네', offsets: { from: 500, to: 500 } }, { text: ' 맞아요.', offsets: { from: 500, to: 500 } }] }
  ] }))

  // when
  const result = assembleResult(raw, '0 -- 1 speaker_0')

  // then
  assert.deepEqual(result.utterances, [{ ord: 0, speakerLabel: 'speaker_0',
    startSec: 0, endSec: 1, text: '네 맞아요.' }])
})
