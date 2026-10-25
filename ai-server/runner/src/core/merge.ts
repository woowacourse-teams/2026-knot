import type { MergedUtterance, SpeakerPiece, SpeakerSegment, SttSegment, SttWord } from './types'

/** 배정할 화자를 찾지 못한 조각에 붙는 라벨 */
const UNKNOWN_SPEAKER = 'UNKNOWN'

/** 전체·구간 서버 전사는 짧은 실제 화자를 길이만으로 지우지 않는 동일 정책을 쓴다. */
export const SERVER_SPEAKER_POLICY = {
  isMinorSpeakerAbsorbed: false,
  isOrphanUtteranceAbsorbed: false
} as const

/** 이보다 짧은 발화는 맞장구·잡음일 가능성이 높아 이웃 발화에 흡수한다 */
const MIN_UTTERANCE_SEC = 0.5

/**
 * 어느 화자 구간과도 겹치지 않는 단어를 가까운 구간에 붙일 때 허용하는 최대 거리.
 * 화자 전환 경계의 단어가 타임스탬프 오차로 구간 사이에 빠지는 걸 보정한다.
 */
const NEAREST_SPEAKER_TOLERANCE_SEC = 1

/**
 * 총 발화 시간이 이보다 짧은 화자는 파편 클러스터로 보고 이웃 주요 화자에 흡수한다.
 * 짧은 구간의 불안정한 임베딩으로 생긴 화자 분할을 보정한다.
 */
const MINOR_SPEAKER_TOTAL_SEC = 10

/** 다음 단어까지 이만큼 쉬면 문장부호가 없어도 문장이 끝난 것으로 본다 */
const SENTENCE_GAP_SEC = 1

/** whisper.cpp는 세그먼트 끝 단어에 길이 0을 자주 주므로 표를 잃지 않게 최소 길이로 센다 */
const MIN_VOTE_WEIGHT_SEC = 0.05

/**
 * 문장이 이보다 길어지면 다음 Whisper 세그먼트 경계에서 끊는다. 문장부호가 빠진 전사·반복 환각 구간에서
 * 한 "문장"이 수십 초로 커져 다른 화자의 말을 삼키는 것을 막는다.
 */
const MAX_SENTENCE_SEC = 8

const SENTENCE_END_PATTERN = /[.?!]$/

const joinText = (left: string, right: string) => [left, right].filter(Boolean).join(' ').trim()

/** 화자 구간을 start 순으로 정렬하고, 이진 탐색 종료 조건용 누적 최대 end를 함께 만든다 */
const indexSpeakerSegments = (speakerSegments: SpeakerSegment[]) => {
  const sorted = [...speakerSegments].sort((a, b) => a.start - b.start)
  const prefixMaxEnd: number[] = []

  sorted.forEach((segment, index) => {
    prefixMaxEnd.push(index === 0 ? segment.end : Math.max(prefixMaxEnd[index - 1], segment.end))
  })

  return { sorted, prefixMaxEnd }
}

/** sorted에서 start가 end 이상인 첫 인덱스 (그 앞쪽만 겹칠 수 있다) */
const upperBoundByStart = ({ sorted, end }: { sorted: SpeakerSegment[]; end: number }) => {
  let low = 0
  let high = sorted.length

  while (low < high) {
    const mid = (low + high) >> 1
    if (sorted[mid].start < end) low = mid + 1
    else high = mid
  }

  return low
}

interface FindSpeakerParams {
  sorted: SpeakerSegment[]
  prefixMaxEnd: number[]
  start: number
  end: number
}

/** 겹침 길이가 가장 긴 화자. 겹치는 구간이 없으면 null */
const findOverlappingSpeaker = ({ sorted, prefixMaxEnd, start, end }: FindSpeakerParams) => {
  let bestSpeaker: string | null = null
  let bestOverlap = 0

  for (let i = upperBoundByStart({ sorted, end }) - 1; i >= 0 && prefixMaxEnd[i] > start; i -= 1) {
    const overlap = Math.min(sorted[i].end, end) - Math.max(sorted[i].start, start)
    if (overlap > bestOverlap) {
      bestOverlap = overlap
      bestSpeaker = sorted[i].speaker
    }
  }

  return bestSpeaker
}

const gapTo = ({ segment, start, end }: { segment: SpeakerSegment; start: number; end: number }) =>
  Math.max(segment.start - end, start - segment.end, 0)

/** 겹치지 않지만 허용 거리 안에 있는 가장 가까운 화자 */
const findNearestSpeaker = ({ sorted, start, end }: Omit<FindSpeakerParams, 'prefixMaxEnd'>) => {
  let bestSpeaker: string | null = null
  let bestGap = NEAREST_SPEAKER_TOLERANCE_SEC

  for (const segment of sorted) {
    const gap = gapTo({ segment, start, end })
    if (gap <= bestGap) {
      bestGap = gap
      bestSpeaker = segment.speaker
    }
  }

  return bestSpeaker
}

const totalDurationBySpeaker = (speakerSegments: SpeakerSegment[]) =>
  speakerSegments.reduce<Map<string, number>>((totals, segment) => {
    const current = totals.get(segment.speaker) ?? 0
    return new Map(totals).set(segment.speaker, current + segment.end - segment.start)
  }, new Map())

/** 시간상 가장 가까운(겹치면 0) 주요 화자 구간의 화자 */
const nearestMajorSpeaker = ({
  majors,
  segment
}: {
  majors: SpeakerSegment[]
  segment: SpeakerSegment
}) =>
  majors.reduce<{ gap: number; speaker: string }>(
    (best, major) => {
      const gap = gapTo({ segment: major, start: segment.start, end: segment.end })
      return gap < best.gap ? { gap, speaker: major.speaker } : best
    },
    { gap: Infinity, speaker: segment.speaker }
  ).speaker

/**
 * 총 발화 시간이 MINOR_SPEAKER_TOTAL_SEC 미만인 군소 화자의 구간을 가장 가까운 주요 화자에게 넘긴다.
 * 주요 화자가 한 명도 없으면(아주 짧은 녹음) 그대로 둔다.
 */
const absorbMinorSpeakers = (speakerSegments: SpeakerSegment[]) => {
  const totals = totalDurationBySpeaker(speakerSegments)
  const majorLabels = new Set(
    [...totals].filter(([, total]) => total >= MINOR_SPEAKER_TOTAL_SEC).map(([label]) => label)
  )
  if (majorLabels.size === 0 || majorLabels.size === totals.size) return speakerSegments

  const majors = speakerSegments.filter((segment) => majorLabels.has(segment.speaker))

  return speakerSegments.map((segment) =>
    majorLabels.has(segment.speaker)
      ? segment
      : { ...segment, speaker: nearestMajorSpeaker({ majors, segment }) }
  )
}

const unitsOf = (segment: SttSegment): SttWord[] =>
  segment.words?.length
    ? segment.words
    : [{ start: segment.start, end: segment.end, text: segment.text }]

interface VoteBySentenceParams {
  pieces: SpeakerPiece[]
  /** pieces와 같은 길이. 그 조각이 Whisper 세그먼트의 마지막 단어인지 */
  isSegmentEnds: boolean[]
}

interface IsSentenceEndParams extends VoteBySentenceParams {
  index: number
  sentenceStart: number
}

const isSentenceEnd = ({ pieces, isSegmentEnds, index, sentenceStart }: IsSentenceEndParams) => {
  const piece = pieces[index]
  const next = pieces[index + 1]
  if (!next || SENTENCE_END_PATTERN.test(piece.text)) return true
  if (next.start - piece.end >= SENTENCE_GAP_SEC) return true

  return isSegmentEnds[index] && piece.end - sentenceStart >= MAX_SENTENCE_SEC
}

/** 문장부호·긴 쉼·길이 상한을 경계로 조각을 문장 단위로 묶는다 */
const groupBySentence = ({ pieces, isSegmentEnds }: VoteBySentenceParams) => {
  const sentences: SpeakerPiece[][] = []
  let current: SpeakerPiece[] = []

  pieces.forEach((piece, index) => {
    current.push(piece)
    const sentenceStart = current[0].start
    if (!isSentenceEnd({ pieces, isSegmentEnds, index, sentenceStart })) return

    sentences.push(current)
    current = []
  })

  return sentences
}

/** 문장 안에서 발화 시간 합이 가장 긴 화자 */
const majoritySpeaker = (sentence: SpeakerPiece[]) => {
  const totals = new Map<string, number>()
  for (const piece of sentence) {
    const weight = Math.max(piece.end - piece.start, MIN_VOTE_WEIGHT_SEC)
    totals.set(piece.speaker, (totals.get(piece.speaker) ?? 0) + weight)
  }

  return [...totals].reduce((best, entry) => (entry[1] > best[1] ? entry : best))[0]
}

/**
 * 단어별 배정을 문장 단위 다수결로 덮어쓴다. 단어 타임스탬프와 화자 구간 경계가 어긋나
 * 문장 끝 단어가 옆 화자로 넘어가며 한 문장이 두 화자로 쪼개지는 것을 막는다.
 */
const voteBySentence = ({ pieces, isSegmentEnds }: VoteBySentenceParams) =>
  groupBySentence({ pieces, isSegmentEnds }).flatMap((sentence) => {
    const speaker = majoritySpeaker(sentence)
    return sentence.map((piece) => ({ ...piece, speaker }))
  })

interface AssignSpeakersParams {
  segments: SttSegment[]
  speakerSegments: SpeakerSegment[]
  /**
   * 참석자 수(`num-clusters`)로 군집한 결과는 클러스터가 전부 실제 화자이므로 false로 준다.
   * 흡수하면 짧게 한 마디 한 참석자가 사라진다.
   */
  isMinorSpeakerAbsorbed?: boolean
}

/**
 * 전사 결과의 각 단어(단어 타임스탬프가 없으면 세그먼트)에 화자를 배정한다.
 * (임계값 폴백이면) 군소 화자를 먼저 흡수한 뒤, 겹치는 구간이 없으면 1초 이내의 가장 가까운 화자 구간,
 * 그것도 없으면 직전 화자, 마지막으로 UNKNOWN 순서로 정한다. 끝으로 문장 단위 다수결로 덮어쓴다.
 */
export const assignSpeakers = ({
  segments,
  speakerSegments,
  isMinorSpeakerAbsorbed = true
}: AssignSpeakersParams) => {
  const { sorted, prefixMaxEnd } = indexSpeakerSegments(
    isMinorSpeakerAbsorbed ? absorbMinorSpeakers(speakerSegments) : speakerSegments
  )
  const pieces: SpeakerPiece[] = []
  const isSegmentEnds: boolean[] = []
  let previousSpeaker: string | null = null

  for (const segment of segments) {
    const units = unitsOf(segment)
    units.forEach((unit, unitIndex) => {
      const found =
        findOverlappingSpeaker({ sorted, prefixMaxEnd, start: unit.start, end: unit.end }) ??
        findNearestSpeaker({ sorted, start: unit.start, end: unit.end })
      if (found) previousSpeaker = found

      pieces.push({
        speaker: found ?? previousSpeaker ?? UNKNOWN_SPEAKER,
        start: unit.start,
        end: unit.end,
        text: unit.text.trim()
      })
      isSegmentEnds.push(unitIndex === units.length - 1)
    })
  }

  return voteBySentence({ pieces, isSegmentEnds })
}

const groupBySpeaker = (pieces: SpeakerPiece[]) =>
  pieces.reduce<SpeakerPiece[]>((grouped, piece) => {
    const last = grouped.at(-1)
    if (!last || last.speaker !== piece.speaker) return [...grouped, { ...piece }]

    return grouped.with(grouped.length - 1, {
      ...last,
      end: piece.end,
      text: joinText(last.text, piece.text)
    })
  }, [])

const findOrphanIndex = (utterances: SpeakerPiece[]) =>
  utterances.length < 2
    ? -1
    : utterances.findIndex((utterance) => utterance.end - utterance.start < MIN_UTTERANCE_SEC)

/** 고아 발화를 앞 발화(없으면 뒤 발화)에 흡수한다. 화자는 흡수하는 쪽을 따른다. */
const absorbOrphanAt = ({ utterances, index }: { utterances: SpeakerPiece[]; index: number }) => {
  const [low, high] = index > 0 ? [index - 1, index] : [index, index + 1]
  const keeper = index > 0 ? utterances[low] : utterances[high]

  return utterances.toSpliced(low, 2, {
    speaker: keeper.speaker,
    start: utterances[low].start,
    end: utterances[high].end,
    text: joinText(utterances[low].text, utterances[high].text)
  })
}

/** 같은 화자의 연속 조각을 하나의 발화로 묶고, 고아 발화를 정리한 뒤 ord를 매긴다 */
export const mergeUtterances = (
  pieces: SpeakerPiece[],
  { isOrphanUtteranceAbsorbed = true }: { isOrphanUtteranceAbsorbed?: boolean } = {}
): MergedUtterance[] => {
  let utterances = groupBySpeaker(pieces)
  let orphanIndex = isOrphanUtteranceAbsorbed ? findOrphanIndex(utterances) : -1

  while (orphanIndex !== -1) {
    utterances = groupBySpeaker(absorbOrphanAt({ utterances, index: orphanIndex }))
    orphanIndex = findOrphanIndex(utterances)
  }

  return utterances.map((utterance, ord) => ({
    ord,
    speakerLabel: utterance.speaker,
    startSec: utterance.start,
    endSec: utterance.end,
    text: utterance.text
  }))
}
