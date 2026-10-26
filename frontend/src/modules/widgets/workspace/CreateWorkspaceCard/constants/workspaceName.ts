/** 워크스페이스 이름 최대 글자 수. 카운터 `n/20`의 분모이기도 해요. */
export const WORKSPACE_NAME_MAX_LENGTH = 20;

/**
 * 워크스페이스 이름에 허용하는 문자. 한글, 영어, 숫자(0~9), 공백만 통과해요.
 *
 * 자모(ㄱ-ㅎ, ㅏ-ㅣ)를 함께 허용하는 이유:
 * IME로 한글을 조합하는 도중("ㄴ" → "노")에 에러가 깜빡이지 않게 하려고요.
 *
 * 숫자는 0~9만 받아요. 전각 숫자(２)나 원 숫자(①)는 허용하지 않은 문자예요.
 */
export const WORKSPACE_NAME_PATTERN = /^[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9 ]*$/;

export const WORKSPACE_NAME_ERROR_MESSAGE =
  "한글, 영어, 숫자와 공백만 사용할 수 있어요.";

/**
 * 생성 요청이 실패했을 때 입력창 아래에 띄울 문구.
 *
 * 형식 오류(400)는 클라이언트 검사를 통과한 값이 서버에서 걸린 경우예요.
 * 규칙이 어긋났다는 뜻이라 같은 문구를 다시 보여줍니다.
 *
 * 인증이 풀린 401은 문구 대신 로그인 화면으로 돌려보내므로 여기 없습니다.
 */
export const WORKSPACE_SUBMIT_ERROR_MESSAGE = {
  invalid: WORKSPACE_NAME_ERROR_MESSAGE,
  forbidden: "보안 확인에 실패했어요. 새로고침 후 다시 시도해 주세요.",
  unknown: "잠시 후 다시 시도해 주세요.",
} as const;
