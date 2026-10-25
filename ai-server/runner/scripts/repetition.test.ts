import assert from 'node:assert/strict'
import { test } from 'node:test'
import { hasDecoderRepetition, prefersRetry } from '../src/core/repetition'
import type { SttSegment } from '../src/core/types'

const segment = (...texts: string[]): SttSegment => ({
  start: 0,
  end: texts.length,
  text: texts.join(' '),
  words: texts.map((text, index) => ({ start: index, end: index + 1, text }))
})

for (const scenario of [
  { name: 'a short token repeated 195 times', words: Array(195).fill('Q.'), expected: true },
  { name: 'a short token repeated 5 times', words: ['네', ...Array(5).fill('Q.'), '네'], expected: false },
  { name: 'a long token repeated 10 times', words: Array(10).fill('그러니까'), expected: true },
  { name: 'a long token repeated 9 times', words: Array(9).fill('그러니까'), expected: false },
  {
    name: 'a long phrase looping three times',
    words: Array(3).fill(['다음', '스프린트에서는', '기능요구사항을', '먼저', '정리하겠습니다']).flat(),
    expected: true
  },
  {
    name: 'a phrase repeated in real speech',
    words: ['이렇게', '이렇게', '이렇게', '하면', '계속', '깎는', '거야', '계속', '깎는', '거야'],
    expected: false
  },
  {
    name: 'the same question asked twice',
    words: [...['Q.', '이건', '어떻게', '처리하면', '되나요?'], '네', ...['Q.', '이건', '어떻게', '처리하면', '되나요?']],
    expected: true
  }
]) {
  test(`decoder repetition detects ${scenario.name}: ${scenario.expected}`, () => {
    // when
    const detected = hasDecoderRepetition([segment(...scenario.words)])

    // then
    assert.equal(detected, scenario.expected)
  })
}

for (const scenario of [
  { name: 'a clean retry', retry: ['네', '설명', '드릴게요', '이제', '시작'], expected: true },
  { name: 'a retry that lost most words', retry: ['네'], expected: false },
  { name: 'a retry that repeats again', retry: Array(10).fill('Q.'), expected: false }
]) {
  test(`retry selection prefers ${scenario.name}: ${scenario.expected}`, () => {
    // given
    const primary = [segment('네', ...Array(6).fill('Q.'))]

    // when
    const preferred = prefersRetry(primary, [segment(...scenario.retry)])

    // then
    assert.equal(preferred, scenario.expected)
  })
}
