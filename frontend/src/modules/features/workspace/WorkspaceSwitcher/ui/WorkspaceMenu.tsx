import styled from "@emotion/styled";
import Unfold from "@primitives/animation/Unfold";
import Avatar from "@primitives/ui/Avatar";
import Divider from "@primitives/ui/Divider";

interface WorkspaceMenuItem {
  id: number;
  name: string;
}

interface WorkspaceMenuProps {
  id: string;
  /** 내가 속한 워크스페이스 목록 */
  workspaces: WorkspaceMenuItem[];
  /** 지금 보고 있는 워크스페이스 ID. 이 항목을 선택된 모양으로 그려요 */
  currentWorkspaceId: number;
  onSelectWorkspace: (workspaceId: number) => void;
  onCreateWorkspace: () => void;
  onLeaveWorkspace: () => void;
  /** 닫혀서 접히는 중인지. 이 동안에는 접히며 사라지고 눌리지 않아요 */
  isClosing: boolean;
}

/**
 * 워크스페이스 이름 아래로 펼치는 워크스페이스 메뉴.
 *
 * 내 워크스페이스 목록, 새 워크스페이스 만들기, 워크스페이스 나가기를 차례로 둬요.
 * 지금 워크스페이스는 체크 아이콘 없이 배경과 글자색으로만 구분해요.
 * 열릴 때는 위쪽 끝에서 펼쳐지고, 닫힐 때는 다시 접히며 사라져요.
 */
export default function WorkspaceMenu({
  id,
  workspaces,
  currentWorkspaceId,
  onSelectWorkspace,
  onCreateWorkspace,
  onLeaveWorkspace,
  isClosing,
}: WorkspaceMenuProps) {
  return (
    <Container
      as="section"
      id={id}
      aria-label="워크스페이스 메뉴"
      data-overlay-state={isClosing ? "closing" : "open"}
      inert={isClosing}
    >
      <SectionLabel>워크스페이스</SectionLabel>

      <WorkspaceList>
        {workspaces.map(({ id: workspaceId, name }) => {
          const isCurrent = workspaceId === currentWorkspaceId;

          return (
            <li key={workspaceId}>
              <WorkspaceItem
                type="button"
                aria-current={isCurrent || undefined}
                $isCurrent={isCurrent}
                onClick={() => onSelectWorkspace(workspaceId)}
              >
                {/* 이름이 바로 옆에 있어 아바타는 읽지 않아요 */}
                <AvatarWrapper aria-hidden>
                  <Avatar label={name} name={name} size={24} />
                </AvatarWrapper>
                <ItemName>{name}</ItemName>
              </WorkspaceItem>
            </li>
          );
        })}
      </WorkspaceList>

      <DividerWrapper>
        <Divider />
      </DividerWrapper>

      <ActionItem type="button" onClick={onCreateWorkspace}>
        새 워크스페이스 만들기
      </ActionItem>

      <DividerWrapper>
        <Divider />
      </DividerWrapper>

      <ActionItem type="button" $isWarning onClick={onLeaveWorkspace}>
        워크스페이스 나가기
      </ActionItem>
    </Container>
  );
}

const Container = styled(Unfold)`
  position: absolute;
  top: calc(100% + 0.25rem); /* 4px */
  left: 0;
  right: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  padding: 0.375rem; /* 6px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;

const SectionLabel = styled.span`
  padding: 0.375rem 0.625rem 0.25rem; /* 6px 10px 4px */
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption01};
`;

const WorkspaceList = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
`;

const WorkspaceItem = styled.button<{ $isCurrent: boolean }>`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
  width: 100%;
  padding: 0.375rem 0.5rem; /* 6px 8px */
  border-radius: 0.5rem; /* 8px */
  background-color: ${({ theme, $isCurrent }) =>
    $isCurrent ? theme.neutral[100] : "transparent"};
  color: ${({ theme, $isCurrent }) =>
    $isCurrent ? theme.neutral[900] : theme.neutral[700]};
  text-align: left;
  transition: background-color 0.2s ease-in;

  &:hover,
  &:focus-visible {
    background-color: ${({ theme }) => theme.neutral[100]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;

const AvatarWrapper = styled.span`
  display: flex;
  flex-shrink: 0;
`;

const ItemName = styled.span`
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  ${({ theme }) => theme.text.caption02};
`;

const DividerWrapper = styled.div`
  padding: 0.3125rem 0; /* 5px 0 */
`;

const ActionItem = styled.button<{ $isWarning?: boolean }>`
  width: 100%;
  padding: 0.5rem 0.625rem; /* 8px 10px */
  border-radius: 0.5rem; /* 8px */
  color: ${({ theme, $isWarning }) =>
    $isWarning ? theme.sub.warning[600] : theme.neutral[700]};
  text-align: left;
  transition: background-color 0.2s ease-in;
  ${({ theme }) => theme.text.caption02};

  &:hover,
  &:focus-visible {
    background-color: ${({ theme }) => theme.neutral[100]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: -2px;
  }
`;
