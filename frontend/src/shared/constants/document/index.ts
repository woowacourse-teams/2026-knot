/**
 * 결정이 없는 회의 문서의 「결정」 구역에 문서 생성기가 그대로 써 넣는 문장 (STT-R23).
 *
 * 문서 본문에서 문단 전체가 이 문장과 같으면 흐리게 그려요.
 * 생성기 출력(09-29 본문 형식)과 글자가 완전히 같아야 하므로 마침표까지 바꾸지 않아요.
 */
export const NO_DECISION_SENTENCE = "이번 회의에서 정해진 내용은 없어요.";

/**
 * 녹음 직후 정리 화면에서 문서 보기로 넘어왔음을 알리는 주소 쿼리(`?from=recording`)의 이름과 값 (DOC-R2).
 *
 * 문서 보기는 이 쿼리가 있을 때만 같은 녹음의 문서를 넘기는 스테퍼를 보여줘요.
 * 문서 탭 · 사이드바 · 홈 카드 · 탐색에서 연 저장 문서에는 붙이지 않아요.
 */
export const DOCUMENT_FROM_PARAM = "from";
export const DOCUMENT_FROM_RECORDING = "recording";
