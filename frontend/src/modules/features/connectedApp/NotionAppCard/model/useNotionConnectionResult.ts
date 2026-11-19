import { searchKeys } from "@api/queryKey/search";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { useToast } from "@provider/context/toastContext";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useSearchParams } from "react-router";

/** 서버가 노션 동의 화면에서 돌려보내며 주소에 붙이는 결과 이름 */
const RESULT_PARAM = "notionConnection";

const CONNECTION_RESULTS = [
  "CONNECTED",
  "NOTION_CONNECTION_ALREADY_CONNECTED",
  "NOTION_GUEST_NOT_ALLOWED",
  "CANCELLED",
  "FAILED",
] as const;

type ConnectionResult = (typeof CONNECTION_RESULTS)[number];

const isConnectionResult = (value: string | null): value is ConnectionResult =>
  CONNECTION_RESULTS.some((result) => result === value);

interface UseNotionConnectionResultParams {
  workspaceId: number;
}

/**
 * 노션 동의 화면에서 돌아온 결과(`?notionConnection=`)를 한 번 읽어 보여 주고 주소에서 지워요.
 *
 * 연결을 마쳤으면 토스트로 알리고, 노션 게스트라 막혔으면 카드가 게스트 안내를 그리도록 알려요.
 * 어느 결과든 노션 연결 조회를 새로 받아요. 모르는 결과는 주소에 그대로 두고 무시해요.
 * 게스트 안내는 주소에서 지운 뒤에도 이 화면을 떠날 때까지 보여요.
 */
export const useNotionConnectionResult = ({
  workspaceId,
}: UseNotionConnectionResultParams) => {
  const [searchParams, setSearchParams] = useSearchParams();
  const queryClient = useQueryClient();
  const { show } = useToast();
  const [isGuestBlocked, setIsGuestBlocked] = useState(false);
  const result = searchParams.get(RESULT_PARAM);

  useEffect(() => {
    if (!isConnectionResult(result)) return;

    if (result === "CONNECTED") show(TOAST_MESSAGES.NOTION_CONNECTED);
    if (result === "NOTION_GUEST_NOT_ALLOWED") setIsGuestBlocked(true);

    void queryClient.invalidateQueries({
      queryKey: searchKeys.notionConnection(workspaceId),
    });
    // 새로고침하거나 뒤로 와도 같은 알림이 다시 뜨지 않게 기록을 바꿔 지워요
    setSearchParams(
      (params) => {
        params.delete(RESULT_PARAM);

        return params;
      },
      { replace: true },
    );
  }, [result, workspaceId, queryClient, show, setSearchParams]);

  return { isGuestBlocked };
};
