import { useQueryClient, type Query, type QueryKey } from "@tanstack/react-query";
import { isUnsupportedApiError } from "@utils/isUnsupportedApiError";
import { useEffect } from "react";

interface UseApiSupportStateParams {
  /** 신규 API를 구독 중인 컴포넌트에서 같은 query-key factory 값을 전달해요. */
  queryKey: QueryKey;
  error: unknown;
}

// 화면을 옮겨도 미지원 이력을 캐시와 함께 기억하고, 캐시가 제거되면 함께 해제돼요.
// 전역 기능 플래그가 아니라 조회 대상별 제한이에요.
const UNSUPPORTED_QUERIES = new WeakSet<Query>();

/**
 * 미지원 뒤 일반 오류가 나도 이전 캐시의 동작을 다시 켜지 않아요.
 * 새 조회가 성공한 경우에만 제한을 풀며, 현재 오류가 일반 오류면 준비 안내 대신 기존 실패 안내를 써요.
 * API 조회는 이 훅 밖에서 구독해요. 성공 상태는 캐시의 존재 여부와 구분해요.
 */
export default function useApiSupportState({ queryKey, error }: UseApiSupportStateParams) {
  const queryClient = useQueryClient();
  const query = queryClient.getQueryCache().find({ queryKey, exact: true });
  const isSuccess = query?.state.status === "success";
  const isPreviouslyUnsupported = query !== undefined && UNSUPPORTED_QUERIES.has(query);
  const isUnsupported = isUnsupportedApiError(error);
  const isUnavailable = isUnsupported || (isPreviouslyUnsupported && !isSuccess);

  useEffect(() => {
    if (query === undefined) return;
    if (isUnsupported) UNSUPPORTED_QUERIES.add(query);
    else if (isSuccess) UNSUPPORTED_QUERIES.delete(query);
  }, [query, isUnsupported, isSuccess]);

  return { isUnsupported, isUnavailable };
}
