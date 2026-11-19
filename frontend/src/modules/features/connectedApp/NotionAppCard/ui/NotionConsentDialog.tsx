import useStartSearchNotionConnectionMutation from "@api/mutations/useStartSearchNotionConnectionMutation";
import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import useKeyDown from "@hooks/common/useKeyDown";
import Pop from "@primitives/animation/Pop";
import Button from "@primitives/ui/Button";
import Dim from "@primitives/ui/Dim";
import { useId } from "react";

import FileIcon from "@/assets/icons/file.svg";
import PlusIcon from "@/assets/icons/plus.svg";
import TeammateIcon from "@/assets/icons/teammate.svg";

interface NotionConsentDialogProps {
  workspaceId: number;
  /** [취소], 바깥 누름, ESC, 연결 시작 실패로 닫을 때 */
  onClose: () => void;
}

/**
 * 노션을 연결하기 전에 보이는 범위를 알리고 동의를 받는 모달(SEARCH-R30, R31).
 *
 * [동의하고 연결]을 누르면 동의를 보내고, 받은 노션 동의 화면 주소로 같은 탭에서 이동해요.
 * 연결 시작이 실패하면 모달을 닫아요. 그사이 다른 멤버가 연결했으면(409) 연결 조회를 새로 받아 카드가 연결됨으로 바뀌어요.
 */
export default function NotionConsentDialog({
  workspaceId,
  onClose,
}: NotionConsentDialogProps) {
  const titleId = useId();
  const { mutate, isPending, isSuccess } =
    useStartSearchNotionConnectionMutation();

  useKeyDown({ key: "Escape", isEnabled: !isPending, onKeyDown: onClose });

  const handleAgreeClick = () =>
    mutate(
      { workspaceId, agreed: true },
      {
        onSuccess: ({ authorizationUrl }) => {
          window.location.href = authorizationUrl;
        },
        onError: onClose,
      },
    );

  return (
    <Dim onClick={isPending ? undefined : onClose}>
      <Container
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onClick={(e) => e.stopPropagation()}
      >
        <Title id={titleId}>노션을 연결하기 전에 확인해 주세요</Title>

        <GuideList>
          <GuideItem>
            <IconWrapper $tone="strong">
              <TeammateIcon size={18} />
            </IconWrapper>
            <span>
              내가 노션에서 볼 수 있는 내용 전부가
              <br />이 워크스페이스 멤버 모두에게 탐색 답변으로 보일 수 있어요.
            </span>
          </GuideItem>
          <GuideItem>
            <IconWrapper $tone="weak">
              <FileIcon size={18} />
            </IconWrapper>
            <span>
              내 개인 페이지도 들어가요.
              <br />
              노션에서 그 페이지를 볼 수 없는 멤버도 보게 돼요.
            </span>
          </GuideItem>
          <GuideItem>
            <IconWrapper $tone="weak">
              <PlusIcon size={18} />
            </IconWrapper>
            <span>멤버는 탐색에서 내 노션에 페이지를 만들 수 있어요.</span>
          </GuideItem>
        </GuideList>

        <Actions>
          <Button
            size="md"
            variant="outline"
            isFullWidth
            disabled={isPending}
            onClick={onClose}
          >
            취소
          </Button>
          {/* 받은 주소로 넘어가는 동안에도 다시 누르지 못하게 처리 중으로 둬요 */}
          <Button
            size="md"
            isFullWidth
            isLoading={isPending || isSuccess}
            onClick={handleAgreeClick}
          >
            동의하고 연결
          </Button>
        </Actions>
      </Container>
    </Dim>
  );
}

/** 피그마 동의 안내: 폭 520px, 안쪽 32px, 묶음 사이 32px */
const Container = styled(Pop)`
  display: flex;
  flex-direction: column;
  gap: 2rem; /* 32px */
  width: 100%;
  max-width: 32.5rem; /* 520px */
  padding: 2rem; /* 32px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
  /* 좁은 화면에서 낱말 중간이 아니라 낱말 사이에서 줄을 바꿔요 */
  word-break: keep-all;
  overflow-wrap: anywhere;

  /* 모바일은 화면 아래에 붙는 시트로 그려요. 위 모서리만 둥글고, 기기 안전 영역만큼 아래를 더 띄워요(RESP-R7) */
  ${MEDIA_QUERY.mobile} {
    max-width: none;
    padding: 1.75rem 1.5rem calc(1.5rem + env(safe-area-inset-bottom)); /* 28px 24px 24px */
    border-radius: 1.25rem 1.25rem 0 0; /* 20px */
  }
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[900]};
  text-align: center;
  overflow-wrap: break-word;
`;

/** 피그마 안내 글: 회색 상자 안쪽 20px, 항목 사이 14px */
const GuideList = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 0.875rem; /* 14px */
  padding: 1.25rem; /* 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

const GuideItem = styled.li`
  display: flex;
  gap: 0.625rem; /* 10px */
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[800]};
`;

/** 아이콘을 글 첫 줄 가운데에 맞춰요. 사람 아이콘만 Neutral/600, 나머지는 Neutral/400이에요 */
const IconWrapper = styled.span<{ $tone: "strong" | "weak" }>`
  display: flex;
  flex-shrink: 0;
  padding-top: 0.1875rem; /* 3px */
  color: ${({ theme, $tone }) =>
    $tone === "strong" ? theme.neutral[600] : theme.neutral[400]};
`;

const Actions = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
`;
