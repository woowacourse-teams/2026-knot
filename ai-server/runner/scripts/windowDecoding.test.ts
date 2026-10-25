import assert from 'node:assert/strict'
import { test } from 'node:test'
import { buildWhisperArgs } from '../src/main/pipeline/whisper'
import { MAXIMUM_WINDOW_FRAMES, validWindow } from './windowResult'

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
