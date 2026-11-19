import styled from "@emotion/styled";
import { useState } from "react";

import ChevronDown from "@/assets/icons/chevronDown.svg";

import { toBodySections } from "../utils/toBodySections";

interface WriteCardBodyProps {
  /** 초안 본문 전체(Markdown) */
  body: string;
}

/**
 * 초안 본문의 앞부분을 보여주고 [전체 보기]로 펼치는 미리보기(SEARCH-R48).
 *
 * 접혀 있으면 정한 높이에서 자르고 아래를 흐리게 덮어, 뒤에 내용이 더 있음을 알려요.
 */
export default function WriteCardBody({ body }: WriteCardBodyProps) {
  const [isExpanded, setIsExpanded] = useState(false);
  const sections = toBodySections(body);

  const handleToggleClick = () => {
    setIsExpanded((previous) => !previous);
  };

  return (
    <Container>
      <Preview $isExpanded={isExpanded}>
        {sections.map(({ heading, lines }, sectionIndex) => (
          // 카드 본문은 보기 전용이라 묶음과 줄 순서가 바뀌지 않아 위치를 key로 써요
          <Section key={sectionIndex}>
            {heading !== null && <Heading>{heading}</Heading>}
            {lines.map((line, lineIndex) => (
              <Line key={lineIndex}>{line}</Line>
            ))}
          </Section>
        ))}
        {!isExpanded && <Fade aria-hidden />}
      </Preview>

      <ToggleButton
        type="button"
        aria-expanded={isExpanded}
        onClick={handleToggleClick}
      >
        {isExpanded ? "접기" : "전체 보기"}
        <ToggleIcon size={12} $isExpanded={isExpanded} />
      </ToggleButton>
    </Container>
  );
}

/** 접힌 미리보기 높이(px 176). Figma 내용이 길 때 접힘 */
const COLLAPSED_HEIGHT = "11rem";
/** 접힌 미리보기 아래를 덮는 흐림 높이(px 56) */
const FADE_HEIGHT = "3.5rem";

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.375rem; /* 6px */
`;

const Preview = styled.div<{ $isExpanded: boolean }>`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  width: 100%;
  max-height: ${({ $isExpanded }) => ($isExpanded ? "none" : COLLAPSED_HEIGHT)};
  padding: 0.75rem; /* 12px */
  overflow: hidden;
  border-radius: 0.5rem; /* 8px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

const Section = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

const Heading = styled.p`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Line = styled.p`
  overflow-wrap: anywhere;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[900]};
`;

// shortcut: 접힌 높이 아래쪽에 고정해 두어, 내용이 접힌 높이보다 조금 짧으면 마지막 줄이 살짝 흐려져요. 실제 넘침을 재야 하면 그때 ResizeObserver로 바꿔요
const Fade = styled.span`
  position: absolute;
  top: calc(${COLLAPSED_HEIGHT} - ${FADE_HEIGHT});
  right: 0;
  left: 0;
  height: ${FADE_HEIGHT};
  background: linear-gradient(
    to bottom,
    ${({ theme }) => theme.neutral[100]}00,
    ${({ theme }) => theme.neutral[100]}
  );
`;

const ToggleButton = styled.button`
  display: inline-flex;
  align-items: center;
  gap: 0.25rem; /* 4px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

const ToggleIcon = styled(ChevronDown)<{ $isExpanded: boolean }>`
  flex-shrink: 0;
  color: ${({ theme }) => theme.neutral[600]};
  transform: ${({ $isExpanded }) => ($isExpanded ? "rotate(180deg)" : "none")};
`;
