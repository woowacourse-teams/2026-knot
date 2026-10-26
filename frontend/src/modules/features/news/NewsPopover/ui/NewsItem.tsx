import styled from "@emotion/styled";
import { getRouterPath, isWorkspaceRouteKey } from "@routes/PATH_ROUTE";
import { Link, useParams } from "react-router";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";
import type { ReleaseNoteItem } from "@/shared/types/releaseNote";

/**
 * 새 소식 패널의 항목 하나. 제목과 설명 아래에 이미지와 「바로 가보기」는 있을 때만 보여 줘요(NEWS-R4).
 *
 * 피드백으로 고친 항목에는 맨 위에 「피드백 덕분에 바뀌었어요」 칩을 붙여요.
 * 「바로 가보기」는 지금 보고 있는 워크스페이스의 화면으로 옮겨요. 앱에 없는 화면 이름이면 버튼을 숨겨요.
 */
export default function NewsItem({
  title,
  description,
  image,
  link,
  isFromFeedback,
}: ReleaseNoteItem) {
  const { workspaceId } = useParams();

  return (
    <Container>
      {isFromFeedback && <Chip>피드백 덕분에 바뀌었어요</Chip>}
      <Title>{title}</Title>
      <Description>{description}</Description>
      {/* 무엇이 바뀌었는지는 제목과 설명이 말해 주므로 이미지는 꾸밈으로 둬요 */}
      {image && <Image src={image} alt="" />}
      {/* 워크스페이스 밖에서 열거나 Notion에 적힌 화면이 앱에 없으면 옮길 주소를 만들 수 없어요 */}
      {link && workspaceId && isWorkspaceRouteKey(link) && (
        <GoLink to={getRouterPath({ routeKey: link, params: { workspaceId } })}>
          바로 가보기
          <ChevronRightIcon size={12} />
        </GoLink>
      )}
    </Container>
  );
}

const Container = styled.li`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

/** 피그마 Chip/새 소식 · 종류=피드백 덕분에 */
const Chip = styled.span`
  align-self: flex-start;
  padding: 0.125rem 0.5rem; /* 2px 8px */
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption01};
`;

const Title = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
`;

const Description = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption02};
`;

const Image = styled.img`
  width: 100%;
  height: 8.25rem; /* 132px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[100]};
  object-fit: cover;
`;

const GoLink = styled(Link)`
  display: inline-flex;
  align-self: flex-start;
  align-items: center;
  gap: 0.125rem; /* 2px */
  padding-top: 0.125rem; /* 2px */
  color: ${({ theme }) => theme.sub.accent[700]};
  ${({ theme }) => theme.text.caption01};
  text-decoration: none;

  &:focus-visible {
    border-radius: 0.25rem; /* 4px */
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
