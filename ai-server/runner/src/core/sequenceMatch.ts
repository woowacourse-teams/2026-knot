/**
 * Python difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes()와 같은 결과를 낸다.
 * 구간 전사 용어 융합은 실험에서 이 정렬로 검증했으므로 같은 규칙을 그대로 쓴다.
 */
export type OpcodeTag = 'equal' | 'replace' | 'delete' | 'insert'
export type Opcode = [tag: OpcodeTag, i1: number, i2: number, j1: number, j2: number]

type Block = [i: number, j: number, size: number]

const indexesOf = <T>(b: T[]) => {
  const indexes = new Map<T, number[]>()
  b.forEach((item, j) => indexes.set(item, [...(indexes.get(item) ?? []), j]))
  return indexes
}

const longestMatch = <T>(
  a: T[],
  indexes: Map<T, number[]>,
  [alo, ahi, blo, bhi]: [number, number, number, number]
): Block => {
  let best: Block = [alo, blo, 0]
  let lengths = new Map<number, number>()
  for (let i = alo; i < ahi; i++) {
    const next = new Map<number, number>()
    for (const j of indexes.get(a[i]) ?? []) {
      if (j < blo) continue
      if (j >= bhi) break
      const size = (lengths.get(j - 1) ?? 0) + 1
      next.set(j, size)
      if (size > best[2]) best = [i - size + 1, j - size + 1, size]
    }
    lengths = next
  }
  return best
}

const matchingBlocks = <T>(a: T[], b: T[]): Block[] => {
  const indexes = indexesOf(b)
  const queue: [number, number, number, number][] = [[0, a.length, 0, b.length]]
  const blocks: Block[] = []
  while (queue.length) {
    const [alo, ahi, blo, bhi] = queue.pop()!
    const [i, j, size] = longestMatch(a, indexes, [alo, ahi, blo, bhi])
    if (!size) continue
    blocks.push([i, j, size])
    if (alo < i && blo < j) queue.push([alo, i, blo, j])
    if (i + size < ahi && j + size < bhi) queue.push([i + size, ahi, j + size, bhi])
  }
  blocks.sort((x, y) => x[0] - y[0] || x[1] - y[1])
  const collapsed = blocks.reduce<Block[]>((result, block) => {
    const last = result.at(-1)
    if (last && last[0] + last[2] === block[0] && last[1] + last[2] === block[1]) {
      return result.with(result.length - 1, [last[0], last[1], last[2] + block[2]])
    }
    return [...result, block]
  }, [])
  return [...collapsed, [a.length, b.length, 0]]
}

export const opcodes = <T>(a: T[], b: T[]): Opcode[] => {
  const result: Opcode[] = []
  let i = 0
  let j = 0
  for (const [ai, bj, size] of matchingBlocks(a, b)) {
    if (i < ai && j < bj) result.push(['replace', i, ai, j, bj])
    else if (i < ai) result.push(['delete', i, ai, j, bj])
    else if (j < bj) result.push(['insert', i, ai, j, bj])
    i = ai + size
    j = bj + size
    if (size) result.push(['equal', ai, i, bj, j])
  }
  return result
}
