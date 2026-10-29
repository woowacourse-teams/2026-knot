import { css, type Theme } from "@emotion/react";
import styled from "@emotion/styled";
import { Fragment } from "react";

import type {
  MarkdownBlock,
  MarkdownHeadingLevel,
} from "../types/markdownBlock";
import Inlines from "./Inlines";

interface BlockProps {
  /** 그릴 블록 하나 (제목 · 문단 · 목록) */
  block: MarkdownBlock;
}

// 본문은 `##`부터 쓰고, 본문을 감싼 제목(문서 제목, 녹음 문서의 「회의 전체 요약」)은 h2예요.
// 그래서 `##`을 h3로 붙여, 화면 낭독기가 제목 단계를 건너뛰지 않게 해요
const HEADING_TAG = {
  1: "h2",
  2: "h3",
  3: "h4",
} as const satisfies Record<MarkdownHeadingLevel, string>;

// 피그마 Fonts 표의 문서 본문 제목 1 · 2. `###`는 피그마에 없어 Label01로 그려요
const HEADING_TEXT = {
  1: "heading03",
  2: "heading04",
  3: "label01",
} as const satisfies Record<MarkdownHeadingLevel, keyof Theme["text"]>;

/**
 * 블록 하나를 종류에 맞는 요소와 글자 크기로 그려요.
 */
export default function Block({ block }: BlockProps) {
  switch (block.type) {
    case "heading":
      return (
        <Heading as={HEADING_TAG[block.level]} $level={block.level}>
          <Inlines inlines={block.inlines} />
        </Heading>
      );
    case "paragraph":
      return (
        <Paragraph $isMuted={block.isMuted}>
          {block.lines.map((line, lineIndex) => (
            // 본문은 읽기 전용이라 렌더 중에 줄 순서가 바뀌지 않아 위치를 key로 써요
            <Fragment key={lineIndex}>
              {lineIndex > 0 && <br />}
              <Inlines inlines={line} />
            </Fragment>
          ))}
        </Paragraph>
      );
    case "list":
      return (
        <List>
          {block.items.map((item, itemIndex) => (
            <BulletItem key={itemIndex}>
              {/* 글 조각을 한 덩어리로 감싸야 flex 칸이 점과 글 두 개가 돼요. 감싸지 않으면 굵은 글마다 칸이 나뉘어 사이가 벌어져요 */}
              <ItemText>
                <Inlines inlines={item} />
              </ItemText>
            </BulletItem>
          ))}
        </List>
      );
    case "orderedList":
      return (
        <OrderedList start={block.start}>
          {block.items.map((item, itemIndex) => (
            <ListItem key={itemIndex}>
              {/* 번호는 점과 달리 내용이라, 장식이 아닌 글자로 그려 화면 낭독기도 읽게 해요 */}
              <ItemNumber>{block.start + itemIndex}.</ItemNumber>
              <ItemText>
                <Inlines inlines={item} />
              </ItemText>
            </ListItem>
          ))}
        </OrderedList>
      );
  }
}

const Heading = styled.h3<{ $level: MarkdownHeadingLevel }>`
  ${({ theme, $level }) => theme.text[HEADING_TEXT[$level]]};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Paragraph = styled.p<{ $isMuted: boolean }>`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme, $isMuted }) =>
    $isMuted ? theme.neutral[600] : theme.neutral[700]};
`;

const listLayout = css`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const List = styled.ul`
  ${listLayout};
`;

const OrderedList = styled.ol`
  ${listLayout};
`;

const ListItem = styled.li`
  display: flex;
  gap: 0.5rem; /* 8px */
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[700]};
`;

const BulletItem = styled(ListItem)`
  /* 점은 낭독기가 읽지 않도록 글자가 아닌 장식으로 그려요. 목록이라는 사실은 ul · li가 알려줘요 */
  &::before {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[500]};
    content: "•";
  }
`;

/** 피그마 `Doc/ListItem · number`의 번호 자리. 두 자리 번호는 글자만큼 넓어져요 */
const ItemNumber = styled.span`
  flex-shrink: 0;
  min-width: 1rem; /* 16px */
  color: ${({ theme }) => theme.neutral[600]};
`;

const ItemText = styled.span`
  /* 긴 항목이 칸 밖으로 넘치지 않고 줄바꿈되게 해요 */
  min-width: 0;
`;
