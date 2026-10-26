import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import { test } from 'node:test'
import { mkdtemp, readFile, writeFile, chmod, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { promisify } from 'node:util'
import { buildWhisperArgs } from '../src/main/pipeline/whisper'
import { buildDiarizeArgs } from '../src/main/pipeline/diarize'
import { normalizeWavFile } from '../src/main/pipeline/normalize'
import {
  assembleResult,
  runServerPipeline,
  siblingScriptPath,
  validateManifest,
  type ServerManifest
} from './serverPipeline'

const execute = promisify(execFile)

const raw = Buffer.from(
  JSON.stringify({ transcription: [{ offsets: { from: 0, to: 1000 }, text: '안녕' }] })
)
test('original Buffer parser preserves Korean and opaque raw string', () => {
  const result = assembleResult(raw, '0 -- 1 speaker_0', 1)
  assert.equal(result.rawWhisperJson, raw.toString('utf8'))
  assert.equal(result.utterances[0].text, '안녕')
})
test('window diarization script path keeps spaces in the runner directory', () => {
  const moduleUrl = pathToFileURL('/Users/knot/Library/Application Support/runner/scripts/serverPipeline.ts').href
  assert.equal(
    siblingScriptPath(moduleUrl, 'windowDiarization.py'),
    '/Users/knot/Library/Application Support/runner/scripts/windowDiarization.py'
  )
})
test('raw STT alone cannot become a complete diarized result', () => {
  assert.throws(() => assembleResult(raw, ''), /DIARIZATION_EMPTY/)
})
test('manifest refuses relative paths and invalid speaker count', () => {
  assert.throws(() => validateManifest({}), /INVALID_MANIFEST/)
  const manifest: ServerManifest = {
    audioPath: '/job/audio.wav',
    outputDirectory: '/job',
    profile: {
      whisperVadEnabled: false,
      whisperBinary: '/models/whisper',
      diarizeBinary: '/models/diarize',
      whisperModel: '/models/whisper.bin',
      vadModel: '/models/vad.bin',
      segmentationModel: '/models/segmentation.onnx',
      embeddingModel: '/models/embedding.onnx',
      sttThreads: 1,
      diarizeThreads: 1
    }
  }
  assert.throws(() => validateManifest({ ...manifest, audioPath: 'audio.wav' }), /INVALID_MANIFEST/)
  assert.throws(() => validateManifest({ ...manifest, speakerCount: 0 }), /INVALID_MANIFEST/)
})
const wav = () => {
  const buffer = Buffer.alloc(32044)
  buffer.write('RIFF')
  buffer.writeUInt32LE(buffer.length - 8, 4)
  buffer.write('WAVE', 8)
  buffer.write('fmt ', 12)
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
test('native arguments preserve Korean and clustering while keeping original audio time', () => {
  assert.deepEqual(
    buildWhisperArgs({
      modelPath: '/models/whisper.bin',
      audioPath: '/job/audio.wav',
      outputPath: '/job/whisper',
      threads: 4,
      vadModelPath: '/models/vad.bin'
    }),
    ['-m', '/models/whisper.bin', '-f', '/job/audio.wav', '-l', 'ko', '-t', '4',
      '--output-json-full', '-of', '/job/whisper', '--no-prints', '--vad', '--vad-model', '/models/vad.bin']
  )
  const parameters = {
    segmentationModelPath: '/models/segmentation.onnx',
    embeddingModelPath: '/models/embedding.onnx',
    audioPath: '/job/audio.wav',
    threads: 2
  }
  assert.deepEqual(buildDiarizeArgs(parameters), [
    '--clustering.cluster-threshold=0.8',
    '--segmentation.pyannote-model=/models/segmentation.onnx',
    '--embedding.model=/models/embedding.onnx',
    '--segmentation.num-threads=2', '--embedding.num-threads=2', '/job/audio.wav'
  ])
  assert.deepEqual(buildDiarizeArgs({ ...parameters, speakerCount: 3 }), [
    '--clustering.num-clusters=3', ...buildDiarizeArgs(parameters).slice(1)
  ])
})

for (const amplitude of [0, 1000]) {
  test(`WAV normalization preserves header and ${amplitude ? 'raises quiet speech' : 'keeps silence'}`, async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'server-normalize-'))
    try {
      const input = wav()
      for (let offset = 44; offset < input.length; offset += 2) {
        input.writeInt16LE((offset % 4 ? -1 : 1) * amplitude, offset)
      }
      const inputPath = path.join(directory, 'audio.wav')
      const outputPath = path.join(directory, 'normalized.wav')
      await writeFile(inputPath, input)
      await normalizeWavFile({ inputPath, outputPath })
      const output = await readFile(outputPath)
      assert.equal(output.length, input.length)
      assert.deepEqual(output.subarray(0, 44), input.subarray(0, 44))
      for (let offset = 44; offset < output.length; offset += 2) {
        assert.equal(output.readInt16LE(offset), amplitude ? (offset % 4 ? -3277 : 3277) : 0)
      }
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })
}
test('headless fake engines produce an atomic complete artifact', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'server-runner-'))
  try {
    const engine = path.join(directory, 'engine')
    await writeFile(
      engine,
      `#!/usr/bin/env python3\nimport sys,json\nif '-of' in sys.argv:\n p=sys.argv[sys.argv.index('-of')+1]+'.json'\n open(p,'w').write(json.dumps({'transcription':[{'offsets':{'from':0,'to':1000},'text':'안녕'}]},ensure_ascii=False))\nelse: print('0 -- 1 speaker_0')\n`
    )
    await chmod(engine, 0o700)
    const audioPath = path.join(directory, 'audio.wav')
    await writeFile(audioPath, wav())
    const manifest: ServerManifest = {
      audioPath,
      outputDirectory: directory,
      speakerCount: 1,
      profile: {
      whisperVadEnabled: false,
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1
      }
    }
    await runServerPipeline(validateManifest(manifest))
    const result = JSON.parse(await readFile(path.join(directory, 'result.json'), 'utf8'))
    assert.equal(result.schemaVersion, 1)
    assert.equal(result.utterances[0].text, '안녕')
    await assert.rejects(readFile(path.join(directory, 'result.json.tmp')))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
test('supervisor deadline terminates runner and SIGTERM-resistant native engines', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'server-timeout-'))
  try {
    const engine = path.join(directory, 'engine')
    await writeFile(
      engine,
      '#!/usr/bin/env python3\nimport signal,time,pathlib\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\npathlib.Path(__file__).with_name("resistant.ready").write_text("ready")\ntime.sleep(30)\n'
    )
    await chmod(engine, 0o700)
    const audioPath = path.join(directory, 'audio.wav')
    await writeFile(audioPath, wav())
    const manifest: ServerManifest = {
      audioPath,
      outputDirectory: directory,
      profile: {
      whisperVadEnabled: false,
        whisperBinary: engine,
        diarizeBinary: engine,
        whisperModel: engine,
        vadModel: engine,
        segmentationModel: engine,
        embeddingModel: engine,
        sttThreads: 1,
        diarizeThreads: 1
      }
    }
    const manifestPath = path.join(directory, 'manifest.json')
    const ownershipPath = path.join(directory, 'process-owner.json')
    await writeFile(manifestPath, JSON.stringify(manifest))
    const start = performance.now()
    await assert.rejects(
      execute('python3', [
        path.resolve('scripts/serverSupervisor.py'),
        '--parent-pid', String(process.pid),
        '--ownership', ownershipPath,
        '--cwd', directory,
        '--deadline-seconds', '2',
        '--', process.execPath,
        path.resolve('node_modules/tsx/dist/cli.mjs'),
        path.resolve('scripts/serverPipeline.ts'), '--manifest', manifestPath
      ], { timeout: 12000 }),
      (error: NodeJS.ErrnoException) => Number(error.code) === 124
    )
    assert.equal(await readFile(path.join(directory, 'resistant.ready'), 'utf8'), 'ready')
    const owner = JSON.parse(await readFile(ownershipPath, 'utf8'))
    assert.equal(owner.state, 'CLEANED')
    const { stdout } = await execute('ps', ['-axo', 'pid=,pgid=,stat='])
    assert.ok(!stdout.trim().split('\n').some((line) => {
      const [, group, state] = line.trim().split(/\s+/)
      return Number(group) === owner.pgid && !state.includes('Z')
    }), 'No live process may remain in the supervised group')
    assert.ok(performance.now() - start >= 2000)
    assert.ok(performance.now() - start < 10000)
    await assert.rejects(readFile(path.join(directory, 'result.json')))
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
test('split Korean token bytes use original latin1 parser, opaque UTF8 remains unchanged', () => {
  const bytes = Buffer.from('안', 'utf8')
  const tokens = [...bytes].map((byte) =>
    Buffer.concat([
      Buffer.from('{"text":"'),
      Buffer.from([byte]),
      Buffer.from('","offsets":{"from":0,"to":1000}}')
    ])
  )
  const source = Buffer.concat([
    Buffer.from('{"transcription":[{"offsets":{"from":0,"to":1000},"text":"안","tokens":['),
    ...tokens.flatMap((token, index) => (index ? [Buffer.from(','), token] : [token])),
    Buffer.from(']}]}')
  ])
  const result = assembleResult(source, '0 -- 1 speaker_0', 1)
  assert.equal(result.utterances[0].text, '안')
  assert.equal(result.rawWhisperJson, source.toString('utf8'))
  assert.ok(result.rawWhisperJson.includes('\ufffd'))
})
test('shared server policy preserves a short speaker with automatic or fixed speaker count', () => {
  const source = Buffer.from(
    JSON.stringify({
      transcription: [
        { offsets: { from: 0, to: 15000 }, text: '주 화자' },
        { offsets: { from: 16000, to: 17000 }, text: '짧은 화자' }
      ]
    })
  )
  const diarization = '0 -- 15 speaker_0\n16 -- 17 speaker_1'
  assert.equal(
    new Set(assembleResult(source, diarization, 2).utterances.map((item) => item.speakerLabel))
      .size,
    2
  )
  assert.equal(
    new Set(assembleResult(source, diarization).utterances.map((item) => item.speakerLabel)).size,
    2
  )
})

for (const failedStage of ['STT_FAILED', 'DIARIZATION_FAILED'] as const) {
  test(`${failedStage} is distinct and never exposes a raw-only completed result`, async () => {
    const directory = await mkdtemp(path.join(os.tmpdir(), 'server-stage-failure-'))
    try {
      const engine = path.join(directory, 'engine')
      const failsStt = failedStage === 'STT_FAILED'
      await writeFile(
        engine,
        `#!/usr/bin/env python3\nimport sys,json\nstt='-of' in sys.argv\nif stt == ${failsStt ? 'True' : 'False'}: sys.exit(7)\nif stt:\n p=sys.argv[sys.argv.index('-of')+1]+'.json'\n open(p,'w').write(json.dumps({'transcription':[{'offsets':{'from':0,'to':1000},'text':'안녕'}]},ensure_ascii=False))\nelse: print('0 -- 1 speaker_0')\n`
      )
      await chmod(engine, 0o700)
      const audioPath = path.join(directory, 'audio.wav')
      await writeFile(audioPath, wav())
      const manifest: ServerManifest = {
        audioPath,
        outputDirectory: directory,
        profile: {
      whisperVadEnabled: false,
          whisperBinary: engine,
          diarizeBinary: engine,
          whisperModel: engine,
          vadModel: engine,
          segmentationModel: engine,
          embeddingModel: engine,
          sttThreads: 1,
          diarizeThreads: 1
        }
      }
      await assert.rejects(runServerPipeline(manifest), new RegExp(failedStage))
      await assert.rejects(readFile(path.join(directory, 'result.json')))
    } finally {
      await rm(directory, { recursive: true, force: true })
    }
  })
}

import { assembleWindowResult, validWindow } from './windowResult'
const windowInput = {pipelineVersion:'pcm-v1',inputManifestSha256:'a'.repeat(64),inputStartFrame:16000,inputEndFrame:48000}
const evidence = {modelVersion:'model-1',speakers:[{speakerId:'speaker_0',embedding:Array(512).fill(0.1),voicedFrames:32000}],segments:[{start:0,end:2,speaker:'speaker_0'}]}
test('window requires actual word timestamps instead of segment midpoint fallback', () => {
  const raw=Buffer.from(JSON.stringify({transcription:[{offsets:{from:0,to:1000},text:' hello'}]}))
  assert.throws(()=>assembleWindowResult(raw,evidence,windowInput),/WORD_TIMESTAMPS_REQUIRED/)
})
test('window preserves local word frames and real speaker evidence',()=>{
  const raw=Buffer.from(JSON.stringify({transcription:[{offsets:{from:0,to:1000},text:' hello',tokens:[{text:' hello',offsets:{from:0,to:1000}}]}]}))
  const result=assembleWindowResult(raw,evidence,windowInput)
  assert.deepEqual(result.window.tokens,[{startFrame:0,endFrame:16000,text:'hello',speakerId:'speaker_0',segment:0}])
  assert.equal(result.window.speakers[0].embedding.length,512)
  assert.equal(result.window.inputStartFrame,16000)
})
test('silent window is valid and nonfinite or wrong-sized speaker vectors are rejected',()=>{
  const raw=Buffer.from('{"transcription":[]}')
  assert.equal(assembleWindowResult(raw,{modelVersion:'model',speakers:[],segments:[]},windowInput).utterances.length,0)
  assert.throws(()=>assembleWindowResult(raw,{...evidence,speakers:[{...evidence.speakers[0],embedding:[NaN]}]},windowInput),/INVALID_RESULT/)
  assert.equal(validWindow({...windowInput,inputEndFrame:16000+130*16000+1}),false)
})

test('window keeps five-second speaker observations and Whisper segment numbers for BE reclustering', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [
    { offsets: { from: 0, to: 500 }, text: ' 첫 문장.', tokens: [{ text: ' 첫', offsets: { from: 0, to: 200 } },
      { text: ' 문장.', offsets: { from: 200, to: 500 } }] },
    { offsets: { from: 600, to: 1000 }, text: ' 다음', tokens: [{ text: ' 다음', offsets: { from: 600, to: 1000 } }] }
  ] }))
  const embedding = Array(512).fill(1 / 3)
  const observed = { ...evidence, observations: [{ start: 0, end: 1.5, speaker: 'speaker_0', embedding }] }

  // when
  const result = assembleWindowResult(raw, observed, windowInput)

  // then
  assert.deepEqual(result.window.tokens.map(token => token.segment), [0, 0, 1])
  assert.deepEqual(result.window.observations.map(({ embedding, ...frames }) => frames),
    [{ startFrame: 0, endFrame: 24000, speakerId: 'speaker_0' }])
  assert.equal(result.window.observations[0].embedding[0], 0.333333333)
})

for (const observation of [
  { start: 0, end: 1, speaker: 'speaker_0', embedding: Array(511).fill(0.1) },
  { start: 0, end: 1, speaker: 'speaker_9', embedding: Array(512).fill(0.1) },
  { start: 0, end: 2.5, speaker: 'speaker_0', embedding: Array(512).fill(0.1) }
]) {
  test(`window rejects an invalid speaker observation ${observation.speaker} ${observation.end}`, () => {
    // given
    const raw = Buffer.from('{"transcription":[]}')

    // when
    const assemble = () => assembleWindowResult(raw, { ...evidence, observations: [observation] }, windowInput)

    // then
    assert.throws(assemble, /INVALID_RESULT/)
  })
}

test('window clips measured word timestamps to audio bounds and discards wholly outside words',()=>{
  const raw=Buffer.from(JSON.stringify({transcription:[{offsets:{from:0,to:2020},text:' first outside',tokens:[{text:' first',offsets:{from:0,to:1000}},{text:' outside',offsets:{from:2000,to:2020}}]}]}))
  const result=assembleWindowResult(raw,evidence,windowInput)
  assert.equal(result.window.tokens.length,1)
  assert.ok(result.window.tokens[0].endFrame<=32000)
})

test('window discards zero-duration words beyond Opus-decoded tail before duration validation',()=>{
  const raw=Buffer.from(JSON.stringify({transcription:[
    {offsets:{from:0,to:1000},text:' kept',tokens:[{text:' kept',offsets:{from:0,to:1000}}]},
    {offsets:{from:15000,to:15000},text:' boundary',tokens:[{text:' boundary',offsets:{from:15000,to:15000}}]},
    {offsets:{from:15010,to:15010},text:' outside',tokens:[{text:' outside',offsets:{from:15010,to:15010}}]}
  ]}))
  const result=assembleWindowResult(raw,evidence,{...windowInput,inputStartFrame:0,inputEndFrame:240000})
  assert.deepEqual(result.window.tokens.map(t=>t.text),['kept'])
})

test('window still rejects negative or isolated zero-duration timestamps inside audio',()=>{
  for (const offsets of [{from:-100,to:100},{from:500,to:500}]) {
    const raw=Buffer.from(JSON.stringify({transcription:[{offsets,text:' invalid',tokens:[{text:' invalid',offsets}]}]}))
    assert.throws(()=>assembleWindowResult(raw,evidence,windowInput),/INVALID_RESULT/)
  }
})

const measuredWords = (words: { text: string; from: number; to: number }[]) => ({
  offsets: { from: Math.min(...words.map(word => word.from)), to: Math.max(...words.map(word => word.to)) },
  text: words.map(word => word.text).join(' '),
  tokens: words.map(word => ({ text: ` ${word.text}`, offsets: { from: word.from, to: word.to } }))
})

for (const scenario of [
  {
    name: 'leading zero-duration words',
    words: [{ text: 'first', from: 500, to: 500 }, { text: 'second', from: 500, to: 1000 }],
    expected: [{ startFrame: 8000, endFrame: 16000, text: 'first second', speakerId: 'speaker_0', segment: 0 }]
  },
  {
    name: 'zero-duration words at frame zero',
    words: [{ text: 'first', from: 0, to: 0 }, { text: 'second', from: 100, to: 1000 }],
    expected: [{ startFrame: 0, endFrame: 16000, text: 'first second', speakerId: 'speaker_0', segment: 0 }]
  },
  {
    name: 'interior zero-duration words',
    words: [
      { text: 'first', from: 0, to: 500 },
      { text: 'second', from: 500, to: 500 },
      { text: 'third', from: 500, to: 1000 }
    ],
    expected: [
      { startFrame: 0, endFrame: 8000, text: 'first second', speakerId: 'speaker_0', segment: 0 },
      { startFrame: 8000, endFrame: 16000, text: 'third', speakerId: 'speaker_0', segment: 0 }
    ]
  },
  {
    name: 'consecutive trailing zero-duration words with an observed later timestamp',
    words: [
      { text: 'first', from: 0, to: 500 },
      { text: 'second', from: 600, to: 600 },
      { text: 'third', from: 600, to: 600 }
    ],
    expected: [{ startFrame: 0, endFrame: 9600, text: 'first second third', speakerId: 'speaker_0', segment: 0 }]
  },
  {
    name: 'consecutive leading zero-duration words',
    words: [
      { text: 'first', from: 100, to: 100 },
      { text: 'second', from: 200, to: 200 },
      { text: 'third', from: 300, to: 1000 }
    ],
    expected: [{ startFrame: 1600, endFrame: 16000, text: 'first second third', speakerId: 'speaker_0', segment: 0 }]
  }
]) {
  test(`window preserves text and measured bounds when grouping ${scenario.name}`, () => {
    // given
    const raw = Buffer.from(JSON.stringify({ transcription: [measuredWords(scenario.words)] }))

    // when
    const result = assembleWindowResult(raw, evidence, windowInput)

    // then
    assert.deepEqual(result.window.tokens, scenario.expected)
    assert.equal(result.utterances.map(utterance => utterance.text).join(' '),
      scenario.words.map(word => word.text).join(' '))
    assert.equal(result.rawWhisperJson, raw.toString('utf8'))
    assert.deepEqual(result.window.speakers, evidence.speakers)
  })
}

test('window rejects zero-duration-only segments instead of borrowing a neighboring segment interval', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [
    measuredWords([{ text: 'measured', from: 0, to: 500 }]),
    measuredWords([{ text: 'unmeasured', from: 500, to: 500 }])
  ] }))

  // when
  const assemble = () => assembleWindowResult(raw, evidence, windowInput)

  // then
  assert.throws(assemble, /INVALID_RESULT/)
})

test('window rejects reversed words even when a measured neighbor is available', () => {
  // given
  const raw = Buffer.from(JSON.stringify({ transcription: [measuredWords([
    { text: 'first', from: 0, to: 500 },
    { text: 'reversed', from: 700, to: 600 },
    { text: 'last', from: 1000, to: 1500 }
  ])] }))

  // when
  const assemble = () => assembleWindowResult(raw, evidence, windowInput)

  // then
  assert.throws(assemble, /INVALID_RESULT/)
})

test('window preserves Korean split-byte words and punctuation when grouping a zero-duration word', () => {
  // given
  const first = Buffer.from(' 첫', 'utf8')
  const raw = Buffer.from(JSON.stringify({ transcription: [{
    offsets: { from: 500, to: 1000 }, text: 'unused', tokens: [
      { text: first.subarray(0, 2).toString('latin1'), offsets: { from: 500, to: 500 } },
      { text: first.subarray(2).toString('latin1'), offsets: { from: 500, to: 500 } },
      { text: Buffer.from(' 측정.', 'utf8').toString('latin1'), offsets: { from: 500, to: 1000 } }
    ]
  }] }), 'latin1')

  // when
  const result = assembleWindowResult(raw, evidence, windowInput)

  // then
  assert.deepEqual(result.window.tokens, [
    { startFrame: 8000, endFrame: 16000, text: '첫 측정.', speakerId: 'speaker_0', segment: 0 }
  ])
  assert.equal(result.rawWhisperJson, raw.toString('utf8'))
})

test('window rejects nonfinite model times even when a measured neighbor is available', () => {
  // given
  const raw = Buffer.from('{"transcription":[{"offsets":{"from":0,"to":1000},"text":"invalid measured",'
    + '"tokens":[{"text":" invalid","offsets":{"from":1e999,"to":1e999}},'
    + '{"text":" measured","offsets":{"from":500,"to":1000}}]}]}')

  // when
  const assemble = () => assembleWindowResult(raw, evidence, windowInput)

  // then
  assert.throws(assemble, /INVALID_RESULT/)
})
