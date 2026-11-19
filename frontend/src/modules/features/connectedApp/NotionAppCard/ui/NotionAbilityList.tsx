import styled from "@emotion/styled";

import FileIcon from "@/assets/icons/file.svg";
import SearchIcon from "@/assets/icons/search.svg";
import TeammateIcon from "@/assets/icons/teammate.svg";

/**
 * 노션 앱 카드의 「자세히 보기」를 펼치면 보이는, 탐색이 노션으로 하는 일 세 가지.
 */
export default function NotionAbilityList() {
  return (
    <Root>
      <List>
        <Ability>
          <IconTile $tone="medium">
            <SearchIcon size={18} />
          </IconTile>
          <Text>
            <Name>노션을 읽고 답해요</Name>
            <Description>
              회의 문서와 노션을 함께 읽고 답하고,
              <br />
              답에 쓴 노션 페이지를 근거로 보여 줘요.
            </Description>
          </Text>
        </Ability>
        <Ability>
          <IconTile $tone="weak">
            <FileIcon size={18} />
          </IconTile>
          <Text>
            <Name>확인을 받고 페이지를 만들어요</Name>
            <Description>
              확인 카드에서 [만들기]를 눌러야
              <br />
              노션에 페이지가 생겨요.
            </Description>
          </Text>
        </Ability>
        <Ability>
          <IconTile $tone="strong">
            <TeammateIcon size={18} />
          </IconTile>
          <Text>
            <Name>멤버 모두가 함께 써요</Name>
            <Description>
              한 사람이 연결하면 다른 멤버는
              <br />
              따로 연결하지 않고 바로 써요.
            </Description>
          </Text>
        </Ability>
      </List>
    </Root>
  );
}

/** 피그마 탐색이 노션으로 하는 일: 회색 상자 안쪽 16px 20px */
const Root = styled.div`
  padding: 1rem 1.25rem; /* 16px 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

const List = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 1.25rem; /* 20px */
`;

const Ability = styled.li`
  display: flex;
  align-items: flex-start;
  gap: 0.75rem; /* 12px */
`;

/** 피그마 아이콘 타일: 36px 흰 상자. 아이콘 색은 피그마의 아이콘마다 달라요 */
const IconTile = styled.span<{ $tone: "strong" | "medium" | "weak" }>`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme, $tone }) =>
    ({
      strong: theme.neutral[600],
      medium: theme.neutral[500],
      weak: theme.neutral[400],
    })[$tone]};
`;

const Text = styled.span`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  min-width: 0;
`;

const Name = styled.span`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Description = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
