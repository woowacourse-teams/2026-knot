import assert from 'node:assert/strict'
import { test } from 'node:test'
import { opcodes } from '../src/core/sequenceMatch'
import type { SttSegment } from '../src/core/types'
import { fuseVocabulary } from '../src/core/vocabularyFusion'

/** 시각이 이어지는 단어들을 여러 segment로 나눈다. */
const segments = (...groups: string[][]): SttSegment[] => {
  let start = 0
  return groups.map((texts) => {
    const words = texts.map((text) => ({ start: start, end: ++start, text }))
    return { start: words[0].start, end: words[words.length - 1].end, text: texts.join(' '), words }
  })
}

const textsOf = (decoded: SttSegment[]) => decoded.map((item) => item.words?.map((word) => word.text))

for (const [a, b, expected] of [
  ['abxcd', 'abcd', [['equal', 0, 2, 0, 2], ['delete', 2, 3, 2, 2], ['equal', 3, 5, 2, 4]]],
  ['qabxcd', 'abycdf', [['delete', 0, 1, 0, 0], ['equal', 1, 3, 0, 2], ['replace', 3, 4, 2, 3],
    ['equal', 4, 6, 3, 5], ['insert', 6, 6, 5, 6]]],
  ['aaabbb', 'ababab', [['delete', 0, 2, 0, 0], ['equal', 2, 4, 0, 2], ['insert', 4, 4, 2, 3],
    ['equal', 4, 5, 3, 4], ['insert', 5, 5, 4, 5], ['equal', 5, 6, 5, 6]]]
] as const) {
  test(`sequence alignment matches Python difflib for ${a} and ${b}`, () => {
    // when
    const result = opcodes([...a], [...b])

    // then
    assert.deepEqual(result, expected)
  })
}

test('vocabulary decode replaces only a misheard domain term between matching words', () => {
  // given
  const primary = segments(['오늘', '하내스', '설정을', '봐요'])
  const alternative = segments(['오늘', '하네스', '설정을', '봐요'])

  // when
  const fused = fuseVocabulary(primary, alternative)

  // then
  assert.deepEqual(textsOf(fused), [['오늘', '하네스', '설정을', '봐요']])
  assert.equal(fused[0].text, '오늘 하네스 설정을 봐요')
})

for (const scenario of [
  {
    name: 'a sentence edit outside the gained term',
    primary: ['오늘', '하내스', '설정을', '봐요'],
    alternative: ['오늘', '하네스', '설정만', '봐요']
  },
  { name: 'an inserted term', primary: ['오늘', '설정을', '봐요'], alternative: ['오늘', '하네스', '설정을', '봐요'] },
  {
    name: 'a replacement wider than three words',
    primary: ['오늘', '하', '내', '스', '설', '봐요'],
    alternative: ['오늘', '하네스', '설정을', '봐요']
  },
  { name: 'a replacement without a left anchor', primary: ['하내스', '설정을'], alternative: ['하네스', '설정을'] },
  {
    name: 'a lost negation',
    primary: ['오늘', '안', '돼요', '봐요'],
    alternative: ['오늘', 'PR', '돼요', '봐요']
  },
  {
    name: 'a changed number',
    primary: ['오늘', '3', '번', '봐요'],
    alternative: ['오늘', 'CI', '번', '봐요']
  }
]) {
  test(`vocabulary decode keeps the default decode for ${scenario.name}`, () => {
    // given
    const primary = segments(scenario.primary)

    // when
    const fused = fuseVocabulary(primary, segments(scenario.alternative))

    // then
    assert.deepEqual(fused, primary)
  })
}

test('a fused term stays in the first replaced segment and an emptied segment is removed', () => {
  // given
  const primary = segments(['오늘', '서브'], ['애이전트'], ['설정을', '봐요'])
  const alternative = segments(['오늘', '서브에이전트', '설정을', '봐요'])

  // when
  const fused = fuseVocabulary(primary, alternative)

  // then
  assert.deepEqual(textsOf(fused), [['오늘', '서브에이전트'], ['설정을', '봐요']])
})
