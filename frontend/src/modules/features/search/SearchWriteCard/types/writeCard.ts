import type { SearchMessage } from "@/shared/types/searchAnswer";

/** 쓰기 확인 카드. 저장된 메시지의 카드와 생성 중 답변의 카드가 같은 모양이에요 */
export type WriteCard = NonNullable<SearchMessage["writeCard"]>;
