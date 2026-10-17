import { keyframes } from "@emotion/react";
import styled from "@emotion/styled";
import { DOCK_HEIGHT } from "@primitives/layout/DockColumn";
import Textarea from "@primitives/ui/Textarea";

import GhostIcon from "@/assets/icons/ghost.svg";
import MicIcon from "@/assets/icons/mic.svg";
import SendIcon from "@/assets/icons/send.svg";

import { useDockRecording } from "./model/useDockRecording";
import { useWorkspaceDock } from "./model/useWorkspaceDock";
import DockHintTooltip from "./ui/DockHintTooltip";
import DockRecordingChip from "./ui/DockRecordingChip";

/** 폭이 벌어지는 동안 안의 내용이 살짝 흐렸다가 또렷해지며 뒤따라 나타나는 모션 */
const fadeIn = keyframes`
  from {
    opacity: 0;
    filter: blur(2px);
  }
`;

/**
 * 폭이 벌어지는 동안 입력창의 글을 줄바꿈하지 않고 붙잡아 두는 모션.
 *
 * 펼치기 시작할 때는 입력창이 아주 좁아서, 내용만큼 높이를 잡는 입력창이 안내 문구나 남겨 둔 글을
 * 한 글자씩 여러 줄로 접어 순간적으로 높아져요. 독은 아래가 고정이라 그만큼 위로 튀었다가 돌아와요.
 * 이 동안은 직접 넣은 줄바꿈에서만 줄을 나눠, 높이가 펼친 뒤의 높이를 넘지 않게 해요.
 */
const holdLines = keyframes`
  from,
  to {
    white-space: pre;
    overflow-x: hidden;
  }
`;

/** 독이 늘고 주는 감속. 빠르게 출발해 끝에서 오래 부드럽게 멈춰요 */
const DOCK_EASING = "cubic-bezier(0.32, 0.72, 0, 1)";

/** 독이 펼쳐지는 시간 */
const DOCK_EXPAND_DURATION = "0.44s";

/**
 * 화면 하단 가운데에 놓이는 독.
 *
 * 접혀 있을 때는 동그란 버튼 하나이고, 누르면 질문 입력창으로 폭이 벌어지며 펼쳐져요.
 * 화면 아무 데서나 글자를 쳐도 같은 자리로 이어지고, 그 방법은 처음 몇 번 말풍선으로 알려줘요.
 * 접혀 있어도 입력창은 보이지 않게 남겨 두고, 글자가 들어오면 같은 입력창 그대로 펼쳐요. 입력창이 바뀌면 조립 중인 한글이 끊기기 때문이에요.
 * 펼친 뒤 독 바깥을 누르면 다시 접혀요.
 * 채팅이 곧 화면인 탐색에서는 접지 않고 늘 펼쳐 둬요.
 *
 * 질문은 어느 화면에서든 이 독이 바로 보내요. 새 대화는 서버가 접수하면 그 대화 화면으로 옮겨 가요.
 * 접혀 있든 펼쳐 있든 회의 녹음(마이크) 슬롯을 두고, 누르면 마이크 권한을 받아 녹음을 시작해요.
 * 녹음 화면이 아닌 곳에서 녹음이 이어지고 있으면 마이크 자리에 녹음 칩을 둬요. 접힌 독에는 중지 버튼도 함께 둬요.
 * 다른 탭의 녹음이면 칩은 점과 시간만 보여 주고 누를 수 없으며 중지 버튼도 두지 않아요.
 * 녹음 화면에서는 마이크를 숨겨요. Figma에서 숨겨져 있는 글 작성 슬롯은 만들지 않아요.
 *
 * 놓일 자리와 펼친 폭은 이 독을 쓰는 레이아웃이 정해요.
 */
export default function WorkspaceDock() {
  const {
    formRef,
    textareaRef,
    isExpanded,
    isHintVisible,
    message,
    canSubmit,
    handleExpand,
    handleChange,
    handleKeyDown,
    handleSubmit,
  } = useWorkspaceDock();
  const {
    isMicVisible,
    isRecordingChipVisible,
    isOtherTabRecording,
    isRecordingPaused,
    elapsedTime,
    handleMicClick,
    handleOpenRecording,
    handleStopRecording,
  } = useDockRecording();

  return (
    <Bar
      ref={formRef}
      $isExpanded={isExpanded}
      $hasMic={isMicVisible}
      $hasRecordingChip={isRecordingChipVisible}
      onSubmit={handleSubmit}
    >
      {isHintVisible && <DockHintTooltip />}

      <InputContainer $isHidden={!isExpanded}>
        <GhostIcon size={24} />

        <MessageField
          ref={textareaRef}
          rows={1}
          aria-label="무엇이든 요청하세요"
          placeholder="무엇이든 요청하세요"
          // 접힌 독에서는 보이지 않으므로 Tab으로 들르지 않아요
          tabIndex={isExpanded ? undefined : -1}
          value={message}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
        />
      </InputContainer>

      {isExpanded ? (
        <>
          <Controls>
            {isMicVisible && (
              <>
                <ControlDivider aria-hidden="true" />
                <MicButton
                  type="button"
                  aria-label="회의 녹음 시작"
                  onClick={handleMicClick}
                >
                  <MicIcon size={24} />
                </MicButton>
              </>
            )}

            {isRecordingChipVisible && (
              <DockRecordingChip
                elapsedTime={elapsedTime}
                isPaused={isRecordingPaused}
                onOpen={isOtherTabRecording ? undefined : handleOpenRecording}
              />
            )}

            <SubmitButton
              type="submit"
              aria-label="보내기"
              disabled={!canSubmit}
            >
              <SendIcon size={16} />
            </SubmitButton>
          </Controls>
        </>
      ) : (
        <>
          <SlotButton
            type="button"
            aria-label="무엇이든 요청하기"
            onClick={handleExpand}
          >
            <GhostIcon size={24} />
          </SlotButton>

          {isMicVisible && (
            <MicButton
              type="button"
              aria-label="회의 녹음 시작"
              onClick={handleMicClick}
            >
              <MicIcon size={24} />
            </MicButton>
          )}

          {isRecordingChipVisible && (
            <>
              <ControlDivider aria-hidden="true" />
              <DockRecordingChip
                elapsedTime={elapsedTime}
                isPaused={isRecordingPaused}
                onOpen={isOtherTabRecording ? undefined : handleOpenRecording}
                onStop={isOtherTabRecording ? undefined : handleStopRecording}
              />
            </>
          )}
        </>
      )}
    </Bar>
  );
}

/**
 * 접힘·펼침을 오가는 독 껍데기.
 *
 * 두 모양을 다른 요소로 두면 모션이 끊기므로, 한 요소의 폭만 바꿔 늘고 줄게 해요.
 * 녹음 칩이 있으면 접힌 폭이 내용만큼(`auto`)이라, `interpolate-size`로 `auto`와 길이 사이도 이어서 움직여요.
 * 이를 지원하지 않는 브라우저에서는 이때만 모션 없이 바로 바뀌어요.
 * 펼칠 때는 길게(0.44초), 접을 때는 조금 짧게(0.36초) 움직이고, 안의 내용은 폭이 어느 정도 벌어진 뒤 뒤따라 나타나요.
 */
const Bar = styled.form<{
  $isExpanded: boolean;
  $hasMic: boolean;
  $hasRecordingChip: boolean;
}>`
  position: relative; /* 안내 말풍선이 이 자리를 기준으로 위에 놓여요 */
  display: flex;
  align-items: flex-end; /* 여러 줄로 자라도 보내기 버튼은 아래에 남아요 */
  gap: ${({ $isExpanded }) =>
    $isExpanded ? "0.625rem" : "0.25rem"}; /* 10px : 4px */
  /* 펼치면 놓인 자리(대화 열과 같은 폭)를 다 채워요.
     접혔을 때는 슬롯(40px) 개수만큼: 마이크까지 있으면 108px, 없으면 64px.
     녹음 칩은 시간이 길어지면 폭이 늘어 내용만큼 둬요 */
  width: ${({ $isExpanded, $hasMic, $hasRecordingChip }) => {
    if ($isExpanded) return "100%";
    if ($hasRecordingChip) return "auto";

    return $hasMic ? "6.75rem" : "4rem"; /* 108px : 64px */
  }};
  min-height: ${DOCK_HEIGHT}; /* 여러 줄이면 이만큼에서부터 늘어나요 */
  padding: ${({ $isExpanded }) =>
    $isExpanded
      ? "0.75rem 0.75rem 0.75rem 1.25rem" /* 12px 12px 12px 20px */
      : "0 0.75rem"}; /* 12px */
  /* 접혔을 때만 동그란 알약이고, 펼친 뒤에는 여러 줄로 자라도 모서리가 30px로 고정돼요 */
  border-radius: ${({ $isExpanded }) =>
    $isExpanded ? "1.875rem" : "6.25rem"}; /* 30px : 100px */
  background-color: ${({ theme }) => theme.neutral[800]};
  box-shadow: ${({ theme }) => theme.shadow03};
  interpolate-size: allow-keywords;
  transition-property: width, padding, gap, border-radius;
  transition-duration: ${({ $isExpanded }) =>
    $isExpanded ? DOCK_EXPAND_DURATION : "0.36s"};
  transition-timing-function: ${DOCK_EASING};

  /* 말풍선은 제 모션이 따로 있으므로 한 줄에 놓이는 것들만 뒤따라 나타나게 해요 */
  & > div,
  & > button,
  & > span {
    animation: ${fadeIn} 0.28s ease-out 0.06s backwards;
  }

  @media (prefers-reduced-motion: reduce) {
    transition: none;

    & > div,
    & > button,
    & > span {
      animation: none;
    }
  }
`;

/** 접힌 독의 탐색·마이크 버튼과 펼친 독의 마이크 버튼. 40px 칸 가운데에 24px 아이콘을 둬요. */
const SlotButton = styled.button`
  display: flex;
  align-self: center;
  align-items: center;
  justify-content: center;
  width: 2.5rem; /* 40px */
  height: 2.5rem;
  flex-shrink: 0;
  border-radius: 1.25rem; /* 20px */
  color: ${({ theme }) => theme.neutral[300]};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** Figma Icon/Mic는 Neutral/500으로 탐색 아이콘보다 한 단계 옅어요. */
const MicButton = styled(SlotButton)`
  color: ${({ theme }) => theme.neutral[500]};
`;

/**
 * 고스트 아이콘과 입력창을 묶는 자리. 여러 줄로 자라면 아이콘은 첫 줄에 붙어요.
 *
 * 접힌 독에서는 커서를 받을 수 있게 남겨 두되 자리를 차지하지 않게 감춰요.
 * `display: none`이나 `visibility: hidden`은 커서를 받지 못해 쓰지 않아요.
 */
const InputContainer = styled.div<{ $isHidden: boolean }>`
  display: flex;
  flex: 1;
  align-self: center;
  align-items: flex-start;
  gap: 0.625rem; /* 10px */
  min-width: 0;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[300]};
  }

  ${({ $isHidden }) =>
    $isHidden &&
    `
    && {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      opacity: 0;
      pointer-events: none;
      /* 펼칠 때 독 안의 다른 것들처럼 뒤따라 나타나도록, 감춰 둔 동안은 모션을 꺼 둬요 */
      animation: none;

      & > textarea {
        animation: none;
      }
    }
  `}
`;

const MessageField = styled(Textarea)`
  flex: 1;
  min-width: 0;
  max-height: 7.5rem; /* 120px — 5줄까지 자라고 더 길어지면 안에서 스크롤해요 */
  overflow-y: auto;
  field-sizing: content;
  background-color: transparent;
  color: ${({ theme }) => theme.neutral[0]};
  ${({ theme }) => theme.text.body01};
  /* 감춰 둔 입력창은 모션이 꺼져 있어, 펼칠 때마다 처음부터 다시 붙잡아요 */
  animation: ${holdLines} ${DOCK_EXPAND_DURATION};

  &::placeholder {
    color: ${({ theme }) => theme.neutral[400]};
  }

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

/**
 * 펼친 독 오른쪽의 구분선·마이크·보내기 묶음.
 *
 * 마이크(40px)와 보내기(36px)는 높이가 달라 서로 가운데를 맞추고, 여러 줄로 자라면 묶음째 아래에 남아요.
 */
const Controls = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.625rem; /* 10px */
`;

/**
 * 입력창과 마이크, 접힌 독의 탐색 버튼과 녹음 칩 사이의 세로 구분선. 8×28 칸 가운데의 1.5px 선이에요.
 *
 * Figma 값(#474747)과 같은 토큰이 없어 어두운 독 위에서 가장 가까운 Neutral/700을 써요.
 */
const ControlDivider = styled.span`
  flex-shrink: 0;
  align-self: center;
  width: 0.5rem; /* 8px */
  height: 1.75rem; /* 28px */
  background: linear-gradient(
      ${({ theme }) => theme.neutral[700]},
      ${({ theme }) => theme.neutral[700]}
    )
    center / 1.5px 100% no-repeat;
`;

const SubmitButton = styled.button`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem;
  border-radius: 62.4375rem;
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[800]};
  transition:
    background-color 0.2s ease-in,
    color 0.2s ease-in;

  &:disabled {
    background-color: ${({ theme }) => theme.neutral[500]};
    color: ${({ theme }) => theme.neutral[700]};
    cursor: not-allowed;
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
