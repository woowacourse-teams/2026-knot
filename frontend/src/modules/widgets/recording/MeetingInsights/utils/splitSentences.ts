const SENTENCE_BOUNDARY = /(?<=[.?!])\s+/;
/** 「네?」 같은 되물음을 질문에서 빼는 길이 */
const QUESTION_MIN_LENGTH = 5;

/** 전사 구간의 글을 문장으로 나눠요. 구간 하나에 여러 문장이 들어 있어요 */
export const splitSentences = (text: string) =>
  text
    .trim()
    .split(SENTENCE_BOUNDARY)
    .filter((sentence) => sentence !== "");

/** 물음표로 끝나는 5글자 이상 문장을 질문으로 봐요 */
export const isQuestionSentence = (sentence: string) =>
  sentence.endsWith("?") && sentence.length >= QUESTION_MIN_LENGTH;

/** 공백을 뺀 글자 수 */
export const countCharacters = (text: string) => text.replace(/\s/g, "").length;
