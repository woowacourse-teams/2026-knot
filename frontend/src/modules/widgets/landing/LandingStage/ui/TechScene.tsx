import styled from "@emotion/styled";
import Chip from "@primitives/ui/Chip";
import type { Ref } from "react";

import { TECH_STORIES } from "../constants/landing";

import ScenePanel, { Lead, Phrase } from "./ScenePanel";
import SceneLayer, { focusRing, interactive, mobile } from "./SceneLayer";

interface TechSceneProps {
  isOn: boolean;
  /** 입자가 카드 모양을 다 갖춰 진짜 카드와 글자를 보여도 되는지 */
  isRevealed: boolean;
  storyIndex: number;
  techRef: Ref<HTMLDivElement>;
  onStorySelect: (index: number) => void;
}

/**
 * 어떤 기술로 어떤 문제를 풀었고 결과가 어땠는지 보여 주는 장면.
 *
 * 왼쪽에서 이야기를 고르면 오른쪽 카드가 문제, 푼 방법, 결과 순서로 바뀌어요.
 * 결과의 수치는 막대로 견주고, 카드가 바뀔 때마다 입자가 새 카드 모양으로 다시 모였다가 글자가 나타나요.
 */
export default function TechScene({ isOn, isRevealed, storyIndex, techRef, onStorySelect }: TechSceneProps) {
  const story = TECH_STORIES[storyIndex];
  const isCardRevealed = isOn && isRevealed;

  return (
    <>
      <ScenePanel
        isOn={isOn}
        eyebrow="어떻게 만들었나요"
        title={
          <>
            보이지 않는 곳에서
            <br />
            일하는 기술.
          </>
        }
      >
        <DesktopLead>
          <Phrase>녹음이 문서가 되고,</Phrase> <Phrase>질문이 답이 되기까지</Phrase>{" "}
          <Phrase>팀이 가장 공들인 세 가지예요.</Phrase>
        </DesktopLead>
        <StoryList>
          {TECH_STORIES.map(({ name }, index) => (
            <StoryButton
              key={name}
              type="button"
              aria-pressed={index === storyIndex}
              $isOn={isOn}
              onClick={() => onStorySelect(index)}
            >
              {name}
            </StoryButton>
          ))}
        </StoryList>
      </ScenePanel>

      <Mock isOn={isOn} ref={techRef}>
        <Card data-sand="o" $isRevealed={isCardRevealed} aria-live="polite">
          <Header>
            <span data-reveal>
              <Chip>{story.tag}</Chip>
            </span>
            <Title data-sand="l">{story.title}</Title>
          </Header>

          <Section>
            <Label data-sand="l">문제</Label>
            <Body data-sand="l">{story.problem}</Body>
          </Section>

          <Section>
            <Label data-sand="l">어떻게 풀었나</Label>
            {story.solution.map((line) => (
              <Body key={line} data-sand="l">
                {line}
              </Body>
            ))}
          </Section>

          <Section>
            <Label data-sand="l">결과</Label>
            <MetricName data-sand="l">{story.metric.name}</MetricName>
            <Bars>
              {story.metric.rows.map(({ label, value, ratio, isOurs }) => (
                <BarRow key={label}>
                  <BarLabel data-sand="l" $isOurs={isOurs}>
                    {label}
                  </BarLabel>
                  <Track data-track>
                    <Bar
                      data-sand="o"
                      data-bar
                      $isOurs={isOurs}
                      style={{ width: `${Math.max(ratio, 0.04) * 100}%` }}
                    />
                  </Track>
                  <BarValue data-sand="l" $isOurs={isOurs}>
                    {value}
                  </BarValue>
                </BarRow>
              ))}
            </Bars>
          </Section>

          <Footer data-footer>
            <Source data-sand="l">{story.source}</Source>
          </Footer>
        </Card>
      </Mock>
    </>
  );
}

const DesktopLead = styled(Lead)`
  ${mobile} {
    display: none;
  }
`;

const StoryList = styled.div`
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem; /* 8px */

  ${mobile} {
    flex-direction: row;
    flex-wrap: wrap;
  }
`;

const StoryButton = styled.button<{ $isOn: boolean }>`
  ${({ $isOn }) => interactive({ isOn: $isOn })};
  min-height: 2.75rem; /* 44px */
  padding: 0 1rem; /* 16px */
  border-radius: 999px;
  ${({ theme }) => theme.text.caption02};
  font-weight: 500;
  text-align: left;
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[800]};
  box-shadow: inset 0 0 0 1px ${({ theme }) => theme.neutral[300]};
  transition:
    background-color 0.25s,
    color 0.25s,
    box-shadow 0.25s;
  ${({ theme }) => focusRing(theme)};

  &[aria-pressed="true"] {
    background-color: ${({ theme }) => theme.primary};
    color: ${({ theme }) => theme.neutral[50]};
    box-shadow: inset 0 0 0 1px ${({ theme }) => theme.primary};
  }

  ${mobile} {
    min-height: 2.5rem; /* 40px */
    padding: 0 0.875rem; /* 14px */
    font-size: 0.8125rem; /* 13px */
  }
`;

const Mock = styled(SceneLayer)`
  position: absolute;
  top: 50%;
  left: calc(69% - 15.625rem); /* 250px */
  transform: translateY(-50%);

  ${mobile} {
    top: 7%;
    left: 50%;
    transform: translateX(-50%) scale(0.66);
    transform-origin: top center;
  }
`;

const Card = styled.div<{ $isRevealed: boolean }>`
  display: flex;
  flex-direction: column;
  gap: 1.125rem; /* 18px */
  width: 31.25rem; /* 500px */
  padding: 1.5rem 1.625rem; /* 24px 26px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme, $isRevealed }) => ($isRevealed ? theme.neutral[0] : "transparent")};
  box-shadow: ${({ theme, $isRevealed }) => ($isRevealed ? theme.shadow02 : "none")};
  transition:
    background-color 0.8s,
    box-shadow 0.8s;

  /* 진짜 글자와 막대는 입자가 다 모인 뒤에 나타나요 */
  [data-sand="l"],
  [data-reveal] {
    opacity: ${({ $isRevealed }) => ($isRevealed ? 1 : 0)};
    transition: opacity 0.7s;
  }

  [data-footer] {
    border-top-color: ${({ theme, $isRevealed }) => ($isRevealed ? theme.neutral[200] : "transparent")};
  }

  [data-track] {
    background-color: ${({ theme, $isRevealed }) => ($isRevealed ? theme.neutral[100] : "transparent")};
  }

  [data-bar] {
    opacity: ${({ $isRevealed }) => ($isRevealed ? 1 : 0)};
  }
`;

const Header = styled.div`
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.5rem; /* 8px */
`;

const Title = styled.span`
  width: max-content;
  ${({ theme }) => theme.text.heading03};
  font-weight: 700;
  color: ${({ theme }) => theme.neutral[900]};
`;

const Section = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

const Label = styled.span`
  width: max-content;
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[900]};
`;

const Body = styled.span`
  width: max-content;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[800]};
`;

const MetricName = styled.span`
  width: max-content;
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Bars = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  padding-top: 0.375rem; /* 6px */
`;

const BarRow = styled.div`
  display: grid;
  grid-template-columns: 5.5rem 1fr 4.5rem; /* 88px 1fr 72px */
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

const BarLabel = styled.span<{ $isOurs: boolean }>`
  width: max-content;
  ${({ theme }) => theme.text.caption02};
  font-weight: ${({ $isOurs }) => ($isOurs ? 600 : 400)};
  color: ${({ theme, $isOurs }) => ($isOurs ? theme.neutral[900] : theme.neutral[600])};
`;

const Track = styled.span`
  display: block;
  height: 0.625rem; /* 10px */
  border-radius: 999px;
  transition: background-color 0.8s;
`;

const Bar = styled.span<{ $isOurs: boolean }>`
  display: block;
  height: 100%;
  border-radius: 999px;
  background-color: ${({ theme, $isOurs }) => ($isOurs ? theme.sub.accent[500] : theme.neutral[400])};
  transition: opacity 0.7s;
`;

const BarValue = styled.span<{ $isOurs: boolean }>`
  justify-self: end;
  width: max-content;
  ${({ theme }) => theme.text.caption02};
  font-weight: ${({ $isOurs }) => ($isOurs ? 700 : 400)};
  color: ${({ theme, $isOurs }) => ($isOurs ? theme.sub.accent[500] : theme.neutral[700])};
`;

const Footer = styled.div`
  padding-top: 0.75rem; /* 12px */
  border-top: 1px solid transparent;
  transition: border-color 0.8s;
`;

const Source = styled.span`
  display: block;
  width: max-content;
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[600]};
`;
