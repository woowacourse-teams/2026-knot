import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useNavigate, useParams } from "react-router";

import type { SearchTurnAnswer } from "../types/searchTurn";

/** 사유마다 보여 줄 안내 글과, 연결된 앱 화면으로 가는 버튼 글자. 버튼이 없는 사유는 `null`이에요 */
const NOTICE: Record<
  NonNullable<SearchTurnAnswer["notionNotice"]>,
  { text: string; action: string | null }
> = {
  NOT_CONNECTED: {
    text: "회의 문서만으로 답했어요. 노션을 연결하면 노션 내용도 함께 찾아요.",
    action: "노션 연결",
  },
  PAGE_NOT_FOUND: {
    text: "노션에서는 맞는 페이지를 찾지 못해 회의 문서만으로 답했어요.",
    action: null,
  },
  READ_FAILED: {
    text: "노션을 읽지 못해 회의 문서만으로 답했어요.",
    action: null,
  },
  CONNECTION_EXPIRED: {
    text: "노션 연결이 만료돼 회의 문서만으로 답했어요.",
    action: "다시 연결",
  },
};

interface NotionNoticeProps {
  reason: NonNullable<SearchTurnAnswer["notionNotice"]>;
}

/**
 * 노션을 읽지 못해 회의 문서만으로 답했을 때 답변 아래에 두는 안내(SEARCH-R39).
 *
 * 연결이 없거나 만료됐으면 [노션 연결]이나 [다시 연결]로 연결된 앱 화면에 가요.
 */
export default function NotionNotice({ reason }: NotionNoticeProps) {
  const { workspaceId = "" } = useParams();
  const navigate = useNavigate();
  // 서버가 새 사유를 더해도 대화 화면이 깨지지 않게, 모르는 사유면 안내를 그리지 않아요
  const notice: (typeof NOTICE)[typeof reason] | undefined = NOTICE[reason];

  if (!notice) return null;

  const { text, action } = notice;

  const handleActionClick = () => {
    navigate(getRouterPath({ routeKey: "APPS", params: { workspaceId } }));
  };

  return (
    <Container>
      <Text>{text}</Text>
      {action && (
        <ActionButton size="sm" variant="outline" onClick={handleActionClick}>
          {action}
        </ActionButton>
      )}
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  align-items: flex-start;
  gap: 0.75rem; /* 12px */
  padding: 1rem; /* 16px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[0]};
`;

const Text = styled.p`
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const ActionButton = styled(Button)`
  flex-shrink: 0;
`;
