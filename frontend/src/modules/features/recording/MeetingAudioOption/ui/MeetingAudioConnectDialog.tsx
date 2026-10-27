import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import useKeyDown from "@hooks/common/useKeyDown";
import Pop from "@primitives/animation/Pop";
import Button from "@primitives/ui/Button";
import Dim from "@primitives/ui/Dim";
import { useId, useState } from "react";

import CheckIcon from "@/assets/icons/check18.svg";

import { MEETING_AUDIO_COPY } from "../constants/meetingAudioCopy";
import type { MeetingLocation } from "../types/meetingSource";
import SharePickerExample from "./SharePickerExample";

const LOCATIONS = Object.keys(
  MEETING_AUDIO_COPY.LOCATIONS,
) as MeetingLocation[];

interface MeetingAudioConnectDialogProps {
  /** 취소 · 바깥 누름 · ESC로 닫을 때. 그때 골라 둔 회의를 연 곳을 받아요 */
  onClose: (location: MeetingLocation) => void;
  /** 「공유할 화면 고르기」를 눌렀을 때. 고른 회의를 연 곳을 받아요 */
  onConfirm: (location: MeetingLocation) => void;
}

/**
 * 공유 창을 열기 전에 회의를 연 곳별 공유 창 설정과 녹음 고지를 보여 주는 모달(STT-R45).
 * Figma `Dialog/화상 회의 연결`(5406-3567)이에요.
 *
 * 회의를 연 곳은 브라우저가 기본이고, 고른 곳에 따라 공유 창에서 고를 것과 예시 그림이 바뀌어요.
 * 바깥을 누르거나 ESC를 눌러도 [취소]와 같이 모달만 닫혀요.
 * 고른 곳은 공유 창 설정 안내에만 쓰고, 닫거나 고를 때 사용 기록에 남기도록 쓰는 쪽에 넘겨요.
 */
export default function MeetingAudioConnectDialog({
  onClose,
  onConfirm,
}: MeetingAudioConnectDialogProps) {
  const id = useId();
  const [location, setLocation] = useState<MeetingLocation>("browserTab");
  const guide = MEETING_AUDIO_COPY.LOCATIONS[location];

  const handleClose = () => onClose(location);

  useKeyDown({ key: "Escape", isEnabled: true, onKeyDown: handleClose });

  return (
    <Dim onClick={handleClose}>
      <Container
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${id}-title`}
        onClick={(e) => e.stopPropagation()}
      >
        <Head>
          <Title id={`${id}-title`}>{MEETING_AUDIO_COPY.DIALOG.TITLE}</Title>
          <Description>{MEETING_AUDIO_COPY.DIALOG.DESCRIPTION}</Description>
        </Head>

        <Where>
          <Question id={`${id}-location`}>
            {MEETING_AUDIO_COPY.DIALOG.LOCATION_QUESTION}
          </Question>
          <Options role="radiogroup" aria-labelledby={`${id}-location`}>
            {LOCATIONS.map((key) => {
              const isSelected = key === location;

              return (
                <Option key={key} $isSelected={isSelected}>
                  <HiddenRadio
                    type="radio"
                    name={id}
                    checked={isSelected}
                    onChange={() => setLocation(key)}
                  />
                  <Radio $isSelected={isSelected} />
                  <OptionText>
                    <OptionLabel>
                      {MEETING_AUDIO_COPY.LOCATIONS[key].LABEL}
                    </OptionLabel>
                    <OptionDescription>
                      {MEETING_AUDIO_COPY.LOCATIONS[key].DESCRIPTION}
                    </OptionDescription>
                  </OptionText>
                </Option>
              );
            })}
          </Options>
        </Where>

        <Steps>
          <StepsTitle>{MEETING_AUDIO_COPY.DIALOG.STEPS_TITLE}</StepsTitle>
          <StepList>
            {guide.STEPS.map((step, index) => (
              <Step key={step}>
                <StepNumber>{index + 1}</StepNumber>
                {step}
              </Step>
            ))}
          </StepList>
          <SharePickerExample
            tab={guide.EXAMPLE_TAB}
            item={guide.EXAMPLE_ITEM}
            audio={guide.EXAMPLE_AUDIO}
          />
        </Steps>

        <Notice>
          <CheckIcon aria-hidden />
          {MEETING_AUDIO_COPY.DIALOG.NOTICE}
        </Notice>

        <Actions>
          <Button size="md" variant="outline" isFullWidth onClick={handleClose}>
            {MEETING_AUDIO_COPY.DIALOG.CANCEL}
          </Button>
          <Button size="md" isFullWidth onClick={() => onConfirm(location)}>
            {MEETING_AUDIO_COPY.DIALOG.CONFIRM}
          </Button>
        </Actions>
      </Container>
    </Dim>
  );
}

const Container = styled(Pop)`
  display: flex;
  flex-direction: column;
  gap: 1.5rem; /* 24px */
  width: 100%;
  max-width: 35rem; /* 560px */
  /* 키가 큰 창이라 낮은 화면에서는 창 안에서 스크롤해요 */
  max-height: 100%;
  overflow-y: auto;
  padding: 2rem; /* 32px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};

  /* 모바일은 화면 아래에 붙는 시트로 그려요. 위 모서리만 둥글고, 기기 안전 영역만큼 아래를 더 띄워요(RESP-R7) */
  ${MEDIA_QUERY.mobile} {
    max-width: none;
    padding: 1.75rem 1.5rem calc(1.5rem + env(safe-area-inset-bottom)); /* 28px 24px 24px */
    border-radius: 1.25rem 1.25rem 0 0; /* 20px */
  }
`;

const Head = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  overflow-wrap: break-word;
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Description = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
  white-space: pre-line;
`;

const Where = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.625rem; /* 10px */
`;

const Question = styled.p`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Options = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
`;

const Option = styled.label<{ $isSelected: boolean }>`
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  gap: 0.625rem; /* 10px */
  min-width: 0;
  /* 피그마는 높이 60px 고정에 위아래 여백 12px이라 글이 여백을 넘쳐요. 높이를 맞추려 위아래 여백을 줄였어요 */
  min-height: 3.75rem; /* 60px */
  padding: 0.375rem 0.875rem; /* 6px 14px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme, $isSelected }) =>
    $isSelected ? theme.neutral[50] : theme.neutral[0]};
  box-shadow: ${({ theme, $isSelected }) =>
    $isSelected
      ? `inset 0 0 0 1.5px ${theme.primary}`
      : `inset 0 0 0 1px ${theme.neutral[200]}`};
  cursor: pointer;

  &:has(:focus-visible) {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** 모양은 옆의 동그라미가 대신 그리고, 선택과 키보드 조작만 맡아요 */
const HiddenRadio = styled.input`
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
`;

const Radio = styled.span<{ $isSelected: boolean }>`
  flex-shrink: 0;
  width: 1.125rem; /* 18px */
  height: 1.125rem;
  border: ${({ theme, $isSelected }) =>
    $isSelected
      ? `5px solid ${theme.primary}`
      : `1.5px solid ${theme.neutral[300]}`};
  border-radius: 50%;
`;

const OptionText = styled.span`
  display: flex;
  flex-direction: column;
  gap: 0.125rem; /* 2px */
  min-width: 0;
`;

const OptionLabel = styled.span`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const OptionDescription = styled.span`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Steps = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  padding: 1rem; /* 16px */
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[50]};
`;

const StepsTitle = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;

const StepList = styled.ol`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

const Step = styled.li`
  display: flex;
  gap: 0.625rem; /* 10px */
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[800]};
`;

const StepNumber = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 1.375rem; /* 22px */
  height: 1.375rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.primary};
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[0]};
`;

const Notice = styled.p`
  display: flex;
  gap: 0.5rem; /* 8px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[700]};

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[800]};
  }
`;

const Actions = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
`;
