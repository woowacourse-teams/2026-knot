import assert from 'node:assert/strict'
import test from 'node:test'
import { assertRestoredVadTimestamps, buildWhisperArgs, parseWhisperOutput } from '../src/main/pipeline/whisper'

const fixture = (segments: unknown[]) => Buffer.from(JSON.stringify({ transcription: segments }))
const token = (text: string, from: number, to: number, t_dtw = -1) =>
  ({ text, offsets: { from, to }, t_dtw })
const segment = (from: number, to: number, tokens: unknown[]) =>
  ({ offsets: { from, to }, text: '', tokens })

test('original token offsets stay on the input timeline across a long segment tail', () => {
  // given
  const raw = fixture([
    segment(0, 20060, [token(' first', 1410, 1800), token(' next', 1850, 2200)])
  ])
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.deepEqual(result[0].words, [
    { start: 1.41, end: 1.8, text: 'first' },
    { start: 1.85, end: 2.2, text: 'next' }
  ])
})

test('a long silence between segments never stretches words or changes their origin', () => {
  // given
  const raw = fixture([
    segment(0, 10000, [token(' before', 1000, 2000)]),
    segment(10000, 30000, [token(' after', 29000, 29500)])
  ])
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.deepEqual(result.map(s => s.words?.[0]), [
    { start: 1, end: 2, text: 'before' },
    { start: 29, end: 29.5, text: 'after' }
  ])
})

test('zero-duration tokens keep their measured point for the shared downstream grouping', () => {
  // given
  const raw = fixture([
    segment(0, 20000, [token(' first', 1000, 1500), token(' last', 1500, 1500)])
  ])
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.deepEqual(result[0].words?.[1], { start: 1.5, end: 1.5, text: 'last' })
})

test('DTW centiseconds convert directly to seconds without a segment-end shift', () => {
  // given
  const raw = fixture([
    segment(0, 20000, [token(' first', 1000, 1500, 120), token(' last', 1500, 1800, 160)])
  ])
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.deepEqual(result[0].words, [
    { start: 1.2, end: 1.6, text: 'first' },
    { start: 1.6, end: 1.8, text: 'last' }
  ])
})

test('DTW preserves a terminal anchor even if the native token offset ends before it', () => {
  // given
  const raw = fixture([segment(0, 40000, [
    token(' first', 36380, 33550, 3640), token(' last', 36380, 35210, 3650)
  ])])
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.deepEqual(result[0].words, [
    { start: 36.4, end: 36.5, text: 'first' },
    { start: 36.5, end: 36.5, text: 'last' }
  ])
})

test('Korean UTF-8 text is preserved together with its original times', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [segment(0, 20000, [
    token(' 한국어', 1500, 2000), token(' 확인', 2100, 2500)
  ])] }))
  // when
  const result = parseWhisperOutput(raw)
  // then
  assert.equal(result[0].text, '한국어 확인')
  assert.equal(result[0].words?.[0].start, 1.5)
})

test('the shared native command supports explicit VAD disabling', () => {
  // given
  const options = { modelPath: '/models/model.bin', audioPath: '/input.wav',
    outputPath: '/output', threads: 4, vadModelPath: '/models/vad.bin', vadEnabled: false }
  // when
  const args = buildWhisperArgs(options)
  // then
  assert.deepEqual(args, ['-m', '/models/model.bin', '-f', '/input.wav', '-l', 'ko', '-t', '4',
    '--output-json-full', '-of', '/output', '--no-prints'])
})

test('VAD is enabled only for an explicitly selected restored-time CLI', () => {
  // given
  const options = { modelPath: '/models/ggml-large-v3-q5_0.bin', audioPath: '/input.wav',
    outputPath: '/output', threads: 4, vadModelPath: '/models/vad.bin', vadEnabled: true }
  // when
  const args = buildWhisperArgs(options)
  // then
  assert.deepEqual(args.slice(-3), ['--vad', '--vad-model', '/models/vad.bin'])
  assert.ok(!args.includes('-dtw'))
})

for (const params of [{}, { vad: true }, { vad: false, token_timestamps: 'original' }]) {
  test('VAD runner rejects an unverified token timeline ' + JSON.stringify(params), () => {
    // given
    const raw = Buffer.from(JSON.stringify({ params }))
    // when
    const verify = () => assertRestoredVadTimestamps(raw)
    // then
    assert.throws(verify, /VAD_TOKEN_TIMESTAMPS_NOT_RESTORED/)
  })
}

test('VAD runner accepts the explicit original-time export marker', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ params: { vad: true, token_timestamps: 'original' } }))
  // when
  const verify = () => assertRestoredVadTimestamps(raw)
  // then
  assert.doesNotThrow(verify)
})
