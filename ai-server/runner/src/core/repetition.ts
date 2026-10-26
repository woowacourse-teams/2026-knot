import type { SttSegment, SttWord } from './types'

/**
 * 디코더 반복 사고 감지. 2026-10 구간 전사 실험에서 채택한 2분 기준선의 재시도 조건을 그대로 쓴다.
 * 감지는 다시 디코딩할 근거일 뿐이며 단어를 지우지 않는다.
 */

/** 이 길이 이하의 짧은 token은 6번, 그보다 긴 token은 10번 연속되면 반복으로 본다 */
const SHORT_TOKEN_LENGTH = 2
const SHORT_TOKEN_RUN = 6
const TOKEN_RUN = 10

/** 3~17단어, 공백 제외 24자 이상인 구절이 겹치지 않게 3번 이상 나오면 반복 고리로 본다 */
const LOOP_MIN_WIDTH = 3
const LOOP_MAX_WIDTH = 17
const LOOP_MIN_CHARACTERS = 24
const LOOP_MIN_OCCURRENCES = 3

/** 'Q.'로 시작해 14단어 안에 물음표로 끝나는 12자 이상 질문이 두 번 나오면 반복으로 본다 */
const QUESTION_MAX_WORDS = 14
const QUESTION_MIN_CHARACTERS = 12

/** 재시도 결과가 기본 결과보다 이만큼 짧아지면 반복 대신 발화를 잃은 것으로 보고 쓰지 않는다 */
const MIN_RETRY_WORD_RATIO = 0.7

const normalize = (text: string) => text.replace(/[^\p{L}\p{N}_]/gu, '').toLowerCase()

const hasTokenRun = (keys: string[]) => {
  for (let start = 0; start < keys.length;) {
    let end = start
    while (end < keys.length && keys[end] === keys[start]) end++
    const run = end - start
    const key = keys[start]
    if (key && ((key.length <= SHORT_TOKEN_LENGTH && run >= SHORT_TOKEN_RUN) || run >= TOKEN_RUN)) return true
    start = end
  }
  return false
}

const hasPhraseLoop = (keys: string[]) => {
  for (let width = LOOP_MIN_WIDTH; width <= LOOP_MAX_WIDTH; width++) {
    const positions = new Map<string, number[]>()
    for (let index = 0; index + width <= keys.length; index++) {
      const phrase = keys.slice(index, index + width)
      if (phrase.join('').length < LOOP_MIN_CHARACTERS) continue
      const key = phrase.join('\u0000')
      positions.set(key, [...(positions.get(key) ?? []), index])
    }
    for (const found of positions.values()) {
      const separate = found.reduce<number[]>((result, index) =>
        !result.length || index >= result[result.length - 1] + width ? [...result, index] : result, [])
      if (separate.length >= LOOP_MIN_OCCURRENCES) return true
    }
  }
  return false
}

const hasRepeatedQuestion = (words: SttWord[]) => {
  const seen = new Set<string>()
  for (let index = 0; index < words.length; index++) {
    if (!/^[QA][.:]$/i.test(words[index].text.trim())) continue
    const limit = Math.min(words.length, index + QUESTION_MAX_WORDS + 1)
    let stop = -1
    for (let next = index + 1; next < limit && stop < 0; next++) {
      if (words[next].text.trim().endsWith('?')) stop = next + 1
    }
    if (stop < 0) continue
    const key = normalize(words.slice(index, stop).map((word) => word.text).join(' '))
    if (key.length < QUESTION_MIN_CHARACTERS) continue
    if (seen.has(key)) return true
    seen.add(key)
  }
  return false
}

const wordsOf = (segments: SttSegment[]) => segments.flatMap((segment) => segment.words ?? [])

export const hasDecoderRepetition = (segments: SttSegment[]) => {
  const words = wordsOf(segments)
  const keys = words.map((word) => normalize(word.text))
  return hasTokenRun(keys) || hasPhraseLoop(keys) || hasRepeatedQuestion(words)
}

/** 재시도 결과에 반복이 없고 단어를 크게 잃지 않았을 때만 기본 결과 대신 쓴다. */
export const prefersRetry = (primary: SttSegment[], retry: SttSegment[]) =>
  hasDecoderRepetition(primary) && !hasDecoderRepetition(retry)
    && wordsOf(retry).length >= MIN_RETRY_WORD_RATIO * wordsOf(primary).length
