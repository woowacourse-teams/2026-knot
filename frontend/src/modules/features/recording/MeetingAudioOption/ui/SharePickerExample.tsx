import styled from "@emotion/styled";

import { MEETING_AUDIO_COPY } from "../constants/meetingAudioCopy";

interface SharePickerExampleProps {
  /** 골라야 하는 공유 창 탭. `DIALOG.EXAMPLE_TABS` 중 하나 */
  tab: string;
  /** 골라야 하는 항목 이름 */
  item: string;
  /** 꼭 켜야 하는 오디오 공유 스위치 이름 */
  audio: string;
}

/**
 * 브라우저 공유 창을 흉내 낸 그림. 어디를 고르고 무엇을 켜야 하는지 짚어 줘요.
 *
 * 같은 내용을 위의 단계 글이 이미 말해서 화면 낭독기에는 숨겨요.
 */
export default function SharePickerExample({
  tab,
  item,
  audio,
}: SharePickerExampleProps) {
  return (
    <Container aria-hidden>
      <Top>
        <Caption>{MEETING_AUDIO_COPY.DIALOG.EXAMPLE_TITLE}</Caption>
        <Tabs>
          {MEETING_AUDIO_COPY.DIALOG.EXAMPLE_TABS.map((label) => (
            <Tab key={label} $isSelected={label === tab}>
              {label}
            </Tab>
          ))}
        </Tabs>
      </Top>
      <Item>
        <Thumb />
        {item}
      </Item>
      <AudioToggle>
        <Switch />
        <AudioLabel>{audio}</AudioLabel>
        <Hint>{MEETING_AUDIO_COPY.DIALOG.EXAMPLE_AUDIO_HINT}</Hint>
      </AudioToggle>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.625rem; /* 10px */
  padding: 0.75rem; /* 12px */
  border-radius: 0.625rem; /* 10px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: inset 0 0 0 1px ${({ theme }) => theme.neutral[200]};
`;

const Top = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem; /* 8px */
`;

const Caption = styled.span`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Tabs = styled.div`
  display: flex;
  gap: 0.25rem; /* 4px */
`;

const Tab = styled.span<{ $isSelected: boolean }>`
  padding: 0.1875rem 0.5rem; /* 3px 8px */
  border-radius: 0.375rem; /* 6px */
  ${({ theme }) => theme.text.caption01};
  background-color: ${({ theme, $isSelected }) =>
    $isSelected ? theme.sub.accent[100] : "transparent"};
  color: ${({ theme, $isSelected }) =>
    $isSelected ? theme.sub.accent[700] : theme.neutral[500]};
`;

const Item = styled.div`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
  padding: 0.5rem; /* 8px */
  border-radius: 0.5rem; /* 8px */
  background-color: ${({ theme }) => theme.sub.accent[100]};
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[800]};
`;

const Thumb = styled.span`
  flex-shrink: 0;
  width: 2.75rem; /* 44px */
  height: 1.75rem; /* 28px */
  border-radius: 0.25rem; /* 4px */
  background-color: ${({ theme }) => theme.neutral[200]};
`;

const AudioToggle = styled.div`
  display: flex;
  align-items: center;
  gap: 0.625rem; /* 10px */
  padding: 0.5rem 0.625rem; /* 8px 10px */
  border-radius: 0.5rem; /* 8px */
  box-shadow: inset 0 0 0 2px ${({ theme }) => theme.sub.accent[500]};
`;

/** 켜진 스위치. 손잡이는 오른쪽 끝에 있어요 */
const Switch = styled.span`
  position: relative;
  flex-shrink: 0;
  width: 1.875rem; /* 30px */
  height: 1.125rem; /* 18px */
  border-radius: 0.5625rem; /* 9px */
  background-color: ${({ theme }) => theme.sub.accent[500]};

  &::after {
    content: "";
    position: absolute;
    top: 0.125rem; /* 2px */
    right: 0.125rem;
    width: 0.875rem; /* 14px */
    height: 0.875rem;
    border-radius: 50%;
    background-color: ${({ theme }) => theme.neutral[0]};
  }
`;

const AudioLabel = styled.span`
  flex: 1;
  min-width: 0;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Hint = styled.span`
  flex-shrink: 0;
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.sub.accent[700]};
`;
