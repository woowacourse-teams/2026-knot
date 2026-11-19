import type { WriteCard } from "../types/writeCard";

/** 실패한 카드의 이유마다 보여 줄 문구. 제목은 모두 「만들지 못했어요」예요(SEARCH-R53) */
export const FAILURE_MESSAGE: Record<
  NonNullable<WriteCard["failureReason"]>,
  string
> = {
  LOCATION_PERMISSION_LOST:
    "이 위치에 페이지를 만들 권한이 없어요. 채팅에 다른 위치를 알려 주세요.",
  NOTION_CONNECTION_CHANGED:
    "카드를 만든 뒤 노션 연결이 바뀌었어요. 다시 요청해 주세요.",
  BODY_TOO_LONG: "내용이 길어 노션이 받지 않았어요. 더 짧게 요청해 주세요.",
  NOTION_NO_RESPONSE:
    "노션에서 응답을 받지 못했어요. 노션에 페이지가 생겼는지 확인해 주세요.",
};

/** 연결이 없거나 만료돼 대기로 돌아온 카드의 안내와 연결된 앱 화면으로 가는 버튼 글자(SEARCH-R52) */
export const CONNECTION_RETRY_MESSAGE = {
  NOTION_RECONNECT_REQUIRED: {
    title: "노션 연결이 만료됐어요",
    description: "다시 연결하면 같은 초안으로 만들 수 있어요.",
    action: "다시 연결",
  },
  NOTION_CONNECTION_REQUIRED: {
    title: "노션이 연결돼 있지 않아요",
    description: "노션을 연결하면 같은 초안으로 만들 수 있어요.",
    action: "연결",
  },
} as const satisfies Partial<
  Record<
    NonNullable<WriteCard["retryReason"]>,
    { title: string; description: string; action: string }
  >
>;

/** 다시 누를 수 있게 대기로 돌아온 카드의 버튼 아래 안내. 노션 요청 많음만 경고 색이에요(SEARCH-R50, R52) */
export const RETRY_CAPTION = {
  NOTION_RATE_LIMITED: {
    text: "노션에 요청이 많아요. 잠시 뒤 다시 눌러 주세요.",
    isWarning: true,
  },
  DATABASE_FIELDS_CHANGED: {
    text: "DB 칸이 바뀌어 미리보기를 새로 불러왔어요. 확인하고 다시 눌러 주세요.",
    isWarning: false,
  },
} as const;

/** 실행 중인 카드의 버튼 아래 안내 */
export const RUNNING_CAPTION = "노션에 페이지를 만드는 중이에요.";

/** DB 칸을 확인하지 못해 [만들기]를 누를 수 없는 카드의 버튼 아래 안내 */
export const NOT_EXECUTABLE_CAPTION = "DB 칸을 확인한 뒤에 만들 수 있어요.";
