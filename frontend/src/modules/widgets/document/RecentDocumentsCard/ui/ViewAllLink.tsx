import styled from "@emotion/styled";
import LinkTo from "@primitives/ui/LinkTo";
import { getRouterPath } from "@routes/PATH_ROUTE";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";

import { useWorkspaceId } from "../model/useWorkspaceId";

/**
 * 카드 머리 오른쪽의 「전체 보기」. 누르면 문서 탭의 「녹음별」 탭으로 가요. 문서 탭은 늘 「녹음별」 탭으로 열려요(DOC-R9).
 *
 * 문서 목록은 따로 조회하므로, 카드가 문서를 받지 못한 동안에도 그대로 둬요.
 */
export default function ViewAllLink() {
  const { workspaceId } = useWorkspaceId();

  return (
    <Root
      href={getRouterPath({
        routeKey: "DOCUMENTS_BY_RECORDING",
        params: { workspaceId: String(workspaceId) },
      })}
    >
      전체 보기
      <ChevronRightIcon size={12} aria-hidden />
    </Root>
  );
}

/** 피그마 「전체 보기」: 글자와 화살표 사이 4px */
const Root = styled(LinkTo)`
  display: inline-flex;
  align-items: center;
  gap: 0.25rem; /* 4px */
  border-radius: 0.25rem; /* 4px */
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption02};

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[500]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
