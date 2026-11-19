import useSearchNotionConnectionQuery from "@api/queries/useSearchNotionConnectionQuery";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useNavigate, useParams } from "react-router";

import { CONNECTION_RETRY_MESSAGE } from "../constants/writeCardMessages";
import WriteCardActions from "./WriteCardActions";
import WriteCardAlert from "./WriteCardAlert";

interface ConnectionRetryFooterProps {
  retryReason: keyof typeof CONNECTION_RETRY_MESSAGE;
  onCancel: () => void;
  onExecute: () => void;
  isCanceling: boolean;
  canExecute: boolean;
}

/**
 * 노션 연결이 만료됐거나 없어 대기로 돌아온 카드의 아래 줄(SEARCH-R52).
 *
 * 지금 노션이 연결돼 있지 않으면 안내와 [다시 연결]이나 [연결]을 보여주고, 누르면 연결된 앱 화면으로 가요.
 * 그 사이 노션이 연결됐으면 같은 초안으로 다시 누를 수 있게 [만들기]를 보여줘요.
 */
export default function ConnectionRetryFooter({
  retryReason,
  onCancel,
  onExecute,
  isCanceling,
  canExecute,
}: ConnectionRetryFooterProps) {
  const { workspaceId = "" } = useParams();
  const navigate = useNavigate();
  const { data: connection } = useSearchNotionConnectionQuery({
    workspaceId: Number(workspaceId),
  });
  const { title, description, action } = CONNECTION_RETRY_MESSAGE[retryReason];

  const handleConnectClick = () => {
    navigate(getRouterPath({ routeKey: "APPS", params: { workspaceId } }));
  };

  if (connection?.status === "CONNECTED") {
    return (
      <WriteCardActions
        onCancel={onCancel}
        onExecute={onExecute}
        isCanceling={isCanceling}
        isRunning={false}
        canExecute={canExecute}
      />
    );
  }

  return (
    <Container>
      <WriteCardAlert title={title} description={description} />
      <ButtonRow>
        <Button
          size="sm"
          variant="outline"
          isLoading={isCanceling}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          size="sm"
          variant="filled"
          disabled={isCanceling}
          onClick={handleConnectClick}
        >
          {action}
        </Button>
      </ButtonRow>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem; /* 12px */
`;

const ButtonRow = styled.div`
  display: flex;
  flex-shrink: 0;
  gap: 0.5rem; /* 8px */
`;
