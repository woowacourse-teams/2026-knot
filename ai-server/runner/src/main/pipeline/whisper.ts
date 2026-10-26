import type { SttSegment, SttWord } from '../../core/types'

const DEFAULT_LANGUAGE = 'ko'
const MS_PER_SEC = 1000
/** t_dtw는 10ms 단위 정수다 */
const MS_PER_DTW_UNIT = 10
const DTW_DISABLED = -1
const SPECIAL_TOKEN_PREFIX = '[_'

interface WhisperToken {
  text: string
  offsets: { from: number; to: number }
  /** -dtw 옵션을 켰을 때만 채워지는 정렬 시각 (10ms 단위), 아니면 -1 */
  t_dtw?: number
}

/** 파서 내부에서 쓰는 정규화된 토큰 — 시간은 ms, DTW가 있으면 그쪽을 쓴다 */
interface NormalizedToken {
  text: string
  from: number
  to: number
}

interface WhisperSegment {
  offsets: { from: number; to: number }
  text: string
  tokens?: WhisperToken[]
}

/**
 * whisper-cli의 --output-json-full 결과는 유효한 UTF-8이 아니다.
 * 토큰이 한글 한 글자를 바이트 단위로 쪼개기 때문에, 바이트를 보존하는 latin1로 읽고
 * 토큰을 이어붙인 뒤에 UTF-8로 되돌려야 한다.
 */
const decodeUtf8 = (latin1Text: string) => Buffer.from(latin1Text, 'latin1').toString('utf-8')

const isSpecialToken = (token: WhisperToken) => token.text.startsWith(SPECIAL_TOKEN_PREFIX)

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

const toWhisperSegments = (parsed: unknown) => {
  if (!isRecord(parsed) || !Array.isArray(parsed.transcription)) {
    throw new Error('whisper 결과 JSON에 transcription 배열이 없습니다')
  }

  return parsed.transcription as WhisperSegment[]
}

/**
 * -dtw를 켜면 토큰마다 정렬 시각 하나(t_dtw)가 붙는다. 끝 시각은 다음 토큰의 시작으로 보고,
 * 마지막 토큰만 원래 offsets의 끝을 쓴다. DTW가 없으면 offsets를 그대로 쓴다.
 */
const normalizeTokens = (tokens: WhisperToken[]): NormalizedToken[] => {
  const hasDtw = tokens.every((token) => (token.t_dtw ?? DTW_DISABLED) >= 0)
  if (!hasDtw) {
    return tokens.map((token) => ({
      text: token.text,
      from: token.offsets.from,
      to: token.offsets.to
    }))
  }

  return tokens.map((token, index) => {
    const from = (token.t_dtw as number) * MS_PER_DTW_UNIT
    const next = tokens[index + 1]

    // 마지막 토큰만 DTW 끝 시각이 없다. offsets.to는 DTW와 조금 어긋나므로 뒤로만 늘린다.
    return {
      text: token.text,
      from,
      to: Math.max(from, next ? (next.t_dtw as number) * MS_PER_DTW_UNIT : token.offsets.to)
    }
  })
}

/** 앞에 공백이 붙은 토큰이 새 단어의 시작이다 */
const groupTokensIntoWords = (tokens: NormalizedToken[]) =>
  tokens.reduce<NormalizedToken[][]>((words, token) => {
    const startsNewWord = words.length === 0 || token.text.startsWith(' ')
    if (startsNewWord) return [...words, [token]]

    return words.with(words.length - 1, [...words[words.length - 1], token])
  }, [])

/**
 * VAD 미사용 또는 native API로 복원한 token 시각을 원래 시간축 그대로 쓴다.
 * segment 끝에 맞춰 이동하거나 segment 길이에 맞춰 늘리면 무음 위에 단어를 놓게 된다.
 * whisper.cpp v1.9.4 CLI는 VAD 사용 시 segment만 원래 시간축으로 변환하고
 * JSON token에는 압축 시간축을 출력한다. JSON에 VAD mapping도 활성화 표시도 없으므로
 * 그 결과를 이 파서로 정확히 복원할 수 없다. VAD 사용 시 runner는 복원 marker를 요구한다.
 * 근거: https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/examples/cli/cli.cpp#L685-L740
 */
const buildWords = (tokens: WhisperToken[]) => {
  const normalized = normalizeTokens(tokens)

  return groupTokensIntoWords(normalized).reduce<SttWord[]>((words, group) => {
    const text = decodeUtf8(group.map((token) => token.text).join('')).trim()
    if (!text) return words

    return [...words, {
      start: group[0].from / MS_PER_SEC,
      end: group[group.length - 1].to / MS_PER_SEC,
      text
    }]
  }, [])
}

/** whisper-cli가 남긴 JSON 파일 내용을 파이프라인 공용 타입으로 바꾼다 */
export const parseWhisperOutput = (raw: Buffer): SttSegment[] => {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw.toString('latin1'))
  } catch {
    throw new Error('whisper 결과 JSON을 읽지 못했습니다')
  }

  return toWhisperSegments(parsed).map((segment) => {
    const tokens = (segment.tokens ?? []).filter((token) => !isSpecialToken(token))
    const words = tokens.length ? buildWords(tokens) : []

    return {
      start: segment.offsets.from / MS_PER_SEC,
      end: segment.offsets.to / MS_PER_SEC,
      text: words.length
        ? words.map((word) => word.text).join(' ')
        : decodeUtf8(segment.text).trim(),
      ...(words.length ? { words } : {})
    }
  })
}

/** VAD 압축 좌표가 표시되지 않는 기존 CLI 결과를 원음 좌표로 오인하지 않는다. */
export const assertRestoredVadTimestamps = (raw: Buffer) => {
  const parsed = JSON.parse(raw.toString('latin1'))
  if (parsed?.params?.vad !== true || parsed?.params?.token_timestamps !== 'original') {
    throw new Error('VAD_TOKEN_TIMESTAMPS_NOT_RESTORED')
  }
}

interface BuildWhisperArgsParams {
  modelPath: string
  audioPath: string
  /** 확장자를 뺀 출력 경로. whisper-cli가 .json을 붙인다 */
  outputPath: string
  threads: number
  vadModelPath?: string
  /** native token getter로 원음 시각을 출력하는 CLI에서만 켠다. */
  vadEnabled?: boolean
  /** 첫 window의 초기 프롬프트. 구간 전사의 고정 용어 디코딩에서만 쓴다. */
  prompt?: string
  /** 이전 window 텍스트를 이어받는 최대 token 수. 0이면 이어받지 않는다. */
  maxContext?: number
}

export const buildWhisperArgs = ({
  modelPath,
  audioPath,
  outputPath,
  threads,
  vadModelPath,
  vadEnabled = Boolean(vadModelPath),
  prompt,
  maxContext
}: BuildWhisperArgsParams) => {
  if (vadEnabled && !vadModelPath) throw new Error('VAD_MODEL_REQUIRED')
  return [
  '-m',
  modelPath,
  '-f',
  audioPath,
  '-l',
  DEFAULT_LANGUAGE,
  '-t',
  String(threads),
  '--output-json-full',
  '-of',
  outputPath,
  '--no-prints',
  ...(vadEnabled ? ['--vad', '--vad-model', vadModelPath!] : []),
  ...(maxContext === undefined ? [] : ['-mc', String(maxContext)]),
  ...(prompt ? ['--prompt', prompt] : [])
  ]
}
