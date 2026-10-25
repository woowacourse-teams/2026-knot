import { opcodes } from './sequenceMatch'
import type { SttSegment, SttWord } from './types'

/**
 * 구간 전사의 두 번째 디코딩에 주는 고정 용어 프롬프트. 사용자 용어 등록은 만들지 않고
 * 2026-10 구간 전사 실험에서 검증한 목록을 그대로 쓴다.
 */
export const VOCABULARY_PROMPT =
  '개발 회의 용어: 하네스, 스킬, 서브 에이전트, 오케스트레이션, 노션, 피그마, API 명세, 기능 요구사항, 도메인 규칙, 이슈, PR, 머지, CI, E2E 테스트, QA 시트, 프론트엔드, 백엔드, props drilling.'

const VOCABULARY = ['하네스', '스킬', '서브에이전트', '오케스트레이션', '노션', '피그마', 'API명세', '기능요구사항',
  '도메인규칙', '이슈', 'PR', '머지', 'CI', 'E2E테스트', 'QA시트', '프론트엔드', '백엔드', 'propsdrilling']

/** 용어 교체가 문장의 부정·필수 여부를 바꾸지 않게 비교하는 표현 */
const NEGATIONS = ['못', '않', '없', '아니', '반드시', '필수', '말고', '안']

/** 교체 범위가 이보다 길면 용어 차이가 아니라 서로 다른 인식으로 본다 */
const MAX_REPLACED_WORDS = 3

/** 두 디코딩의 같은 범위 중앙 시각이 이보다 멀면 같은 말로 보지 않는다 */
const MAX_TIME_DISAGREEMENT_SEC = 2

const normalize = (text: string) => text.replace(/[^\p{L}\p{N}_]/gu, '').toLowerCase()

const joined = (words: SttWord[]) => words.map((word) => word.text).join(' ')

const vocabularyIn = (text: string) => {
  const normalized = normalize(text)
  return new Set(VOCABULARY.map(normalize).filter((key, index) => {
    const isShortAscii = /^[\x00-\x7f]{1,3}$/.test(VOCABULARY[index])
    if (!isShortAscii) return normalized.includes(key)
    return new RegExp(`(?<![a-z])${key}(?![a-z])`).test(text.toLowerCase())
  }))
}

const numbersIn = (text: string) =>
  text.replace(/e2e/gi, '').match(
    /\d+(?:[.,]\d+)*|(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\s*(?:개|번|명|초|분|가지)/g
  ) ?? []

const negationsIn = (text: string) => NEGATIONS.filter((negation) => normalize(text).includes(negation))

const sameList = (first: string[], second: string[]) =>
  first.length === second.length && first.every((item, index) => item === second[index])

interface VocabularyEdit {
  primary: string
  alternative: string
  /** 교체 범위 앞뒤 한 단어를 포함한 대안 디코딩 문장 */
  context: string
  /** context에서 교체 범위 앞에 오는 단어 */
  prefix: string
  gained: Set<string>
}

/** 문자 단위 차이가 모두 새로 얻은 용어 안에서만 생겼는지 확인한다. */
const editsOnlyInsideVocabulary = ({ primary, alternative, context, prefix, gained }: VocabularyEdit) => {
  const replacement = normalize(alternative)
  const normalizedContext = normalize(context)
  const offset = normalize(prefix).length
  const allowed = new Set<number>()
  for (const term of gained) {
    for (let at = normalizedContext.indexOf(term); at >= 0; at = normalizedContext.indexOf(term, at + 1)) {
      for (let index = at; index < at + term.length; index++) {
        if (offset <= index && index < offset + replacement.length) allowed.add(index - offset)
      }
    }
  }
  return opcodes([...normalize(primary)], [...replacement]).every(([tag, , , from, to]) => {
    if (tag === 'equal') return true
    if (from < to) return Array.from({ length: to - from }, (_, index) => from + index).every((index) => allowed.has(index))
    return allowed.has(from - 1) && allowed.has(from)
  })
}

interface Replacement {
  primary: SttWord[]
  alternative: SttWord[]
  range: [number, number, number, number]
}

const midpoint = (words: SttWord[]) => (words[0].start + words[words.length - 1].end) / 2

/** 기본 디코딩의 문장을 지키면서 용어만 바뀐 교체인지 판정한다. */
const isVocabularyReplacement = ({ primary, alternative, range: [i, j, k, l] }: Replacement) => {
  const replaced = primary.slice(i, j)
  const replacing = alternative.slice(k, l)
  const context = joined(alternative.slice(Math.max(0, k - 1), l + 1))
  const existing = vocabularyIn(joined(primary.slice(Math.max(0, i - 1), j + 1)))
  const found = vocabularyIn(context)
  const gained = new Set([...found].filter((term) => !existing.has(term)))
  const lost = [...existing].some((term) => !found.has(term))
  const hasAnchors = i > 0 && j < primary.length && k > 0 && l < alternative.length
  if (!gained.size || lost || !hasAnchors || Math.max(j - i, l - k) > MAX_REPLACED_WORDS) return false
  if (normalize(primary[i - 1].text) !== normalize(alternative[k - 1].text)) return false
  if (normalize(primary[j].text) !== normalize(alternative[l].text)) return false
  if (!sameList(numbersIn(joined(replaced)), numbersIn(joined(replacing)))) return false
  if (!sameList(negationsIn(joined(replaced)), negationsIn(joined(replacing)))) return false
  if (Math.abs(midpoint(replaced) - midpoint(replacing)) > MAX_TIME_DISAGREEMENT_SEC) return false
  if (replacing[0].start < primary[i - 1].start || replacing[replacing.length - 1].start > primary[j].start) return false
  return editsOnlyInsideVocabulary({
    primary: joined(replaced),
    alternative: joined(replacing),
    context,
    prefix: alternative[k - 1].text,
    gained
  })
}

/**
 * 기본 디코딩 결과를 유지하고, 용어 프롬프트 디코딩이 앞뒤 단어가 같은 좁은 범위에서 용어만
 * 새로 맞힌 곳만 그 단어로 바꾼다. 숫자·부정·시각이 달라지는 교체와 삽입·삭제는 받지 않는다.
 * 교체한 단어는 기본 디코딩에서 바뀐 첫 단어의 segment에 두고, 그래서 비게 된 segment는 뺀다.
 */
export const fuseVocabulary = (primary: SttSegment[], alternative: SttSegment[]): SttSegment[] => {
  const words = primary.flatMap((segment, index) => (segment.words ?? []).map((word) => ({ word, index })))
  const primaryWords = words.map(({ word }) => word)
  const alternativeWords = alternative.flatMap((segment) => segment.words ?? [])
  const fused = primary.map((): SttWord[] => [])
  const changed = new Set<number>()
  for (const [tag, i, j, k, l] of opcodes(primaryWords.map((word) => normalize(word.text)),
    alternativeWords.map((word) => normalize(word.text)))) {
    if (tag === 'replace' && isVocabularyReplacement({ primary: primaryWords, alternative: alternativeWords, range: [i, j, k, l] })) {
      fused[words[i].index].push(...alternativeWords.slice(k, l))
      for (const { index } of words.slice(i, j)) changed.add(index)
      continue
    }
    for (const { word, index } of words.slice(i, j)) fused[index].push(word)
  }
  return primary.flatMap((segment, index) => {
    if (!changed.has(index)) return [segment]
    if (!fused[index].length) return []
    return [{ ...segment, text: joined(fused[index]), words: fused[index] }]
  })
}
