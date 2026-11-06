import ParticleIcon from "@composites/ParticleIcon";
import ParticleOrb from "@composites/ParticleOrb";
import { keyframes } from "@emotion/react";
import styled from "@emotion/styled";
import { DOCK_HEIGHT } from "@primitives/layout/DockColumn";
import Textarea from "@primitives/ui/Textarea";
import { useRef } from "react";

import SendIcon from "@/assets/icons/send.svg";

import { useDockHint } from "./model/useDockHint";
import { useDockHoverGuide } from "./model/useDockHoverGuide";
import { useDockOpen } from "./model/useDockOpen";
import { useGuidePresence } from "./model/useGuidePresence";
import { useDockParticleEffects } from "./model/useDockParticleEffects";
import { useDockSplitTargets } from "./model/useDockSplitTargets";
import { useDockRecording } from "./model/useDockRecording";
import { useWorkspaceDock } from "./model/useWorkspaceDock";
import { DOCK_ORB_CANVAS_SIZE, DOCK_ORB_RADIUS } from "./constants/dockOrb";
import DockChatButton from "./ui/DockChatButton";
import DockGuide from "./ui/DockGuide";
import DockMicButton from "./ui/DockMicButton";
import DockParticles from "./ui/DockParticles";
import DockRecordingChip from "./ui/DockRecordingChip";
import DockSeed from "./ui/DockSeed";
import { drawSendIcon } from "./utils/drawDockIcons";

/**
 * 폭이 벌어지는 동안 안의 내용이 살짝 흐렸다가 또렷해지며 뒤따라 나타나는 모션.
 *
 * 투명에서 시작하지 않고 옅게 보이는 데서 시작해요. 탭을 옮겨 독이 펼쳐지고 접힐 때마다 안의 내용이 새로 그려지는데,
 * 투명에서 시작하면 늦게 나타나는 동안 아이콘까지 다 사라진 빈 독이 보여 깜빡여 보여요.
 */
const fadeIn = keyframes`
  from {
    opacity: 0.6;
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
 *
 * 랜딩의 입자가 독에도 이어져요. 녹음이 없을 때는 Figma 독 그대로(Neutral 800 알약) 늘 보이고, 입자는 독 안의 구슬에만 있어요.
 * 채팅 버튼과 녹음 버튼은 둘 다 입자 구슬이에요. 채팅은 점 세 개가 든 말풍선으로 모여 있다가 올리면 점이 입력 중처럼 튀고,
 * 녹음은 고리 안 붉은 원이다가 올리면 입자로 분해됐다 마이크가 돼요. 누르면 녹음이 열릴 때까지 구슬이 밝은 구름으로 바뀌어 빠르게 돌며 기다려요.
 * 다른 화면에서 녹음이 이어지면 접힌 독 자리에 녹음 화면과 같은 먹색 입자 파형이 길게 흐르고(`DockSeed`), 올리면 독으로 모였다가 벗어나면 다시 파형이 돼요. 독 안의 녹음 칩은 밝은 입자 파형이에요.
 * 첫 사용 안내는 홈에서 독이 접혀 쉬고 있을 때만 뜨고, 처음 세 번의 방문 동안은 채팅·녹음 버튼에 올리면 한 줄 안내가 버튼마다 두 번까지 떠요(`DockGuide`).
 * 보내기 화살표는 보낼 수 있게 될 때 입자로 모였다가 진짜 아이콘이 돼요. 독 바탕에는 입자를 깔지 않고, 녹음 버튼을 누르거나 보낼 때만 입자가 터지며 테두리를 돌아요.
 * 캔버스를 못 쓰거나 움직임 줄이기 설정이면 파형 없이 늘 열린 독이라, 기존 동작과 접근성 이름은 어느 환경에서나 그대로예요.
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
    isExpandedByScreen,
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
    isRecordingStarting,
    isRecordingPaused,
    elapsedTime,
    analyser,
    handleMicClick,
    handleOpenRecording,
    handleStopRecording,
  } = useDockRecording();
  // 첫 사용 안내는 홈에서 독이 접혀 쉬고 있을 때만, 버튼 안내는 첫 사용 안내가 없을 때 올리면 떠요
  const isRecordingBusy = isRecordingChipVisible || isRecordingStarting;
  const { isHintVisible } = useDockHint({ isDockExpanded: isExpanded, isRecordingBusy });
  const stageRef = useRef<HTMLDivElement>(null);
  const { hoverGuide, bind: bindHoverGuide } = useDockHoverGuide({
    stageRef,
    isEnabled: !isExpanded && !isRecordingBusy && !isHintVisible,
  });
  // 사라질 때 모션을 보여 주려고 잠깐 더 그려요
  const guide = useGuidePresence(isHintVisible ? { kind: "first", tailOffset: 0 } : hoverGuide);
  const { isSeedSupported, isOpen, isClosedByScreen, open, enter, leave, scheduleClose } =
    useDockOpen({
      stageRef,
      isExpanded,
      isExpandedByScreen,
      hasMessage: message.trim().length > 0,
      hasWaveform: isRecordingChipVisible,
    });
  const { splitTargets } = useDockSplitTargets({
    formRef,
    isOpen,
    isExpanded,
    isMicVisible,
    isRecordingChipVisible,
  });
  const { particlesRef, sendIconRef, burstAt, traceFrom } =
    useDockParticleEffects({ formRef, canSubmit });

  return (
    <Stage
      ref={stageRef}
      onPointerEnter={(event) => {
        // 마우스를 올리면 입자 자리에 진짜 독이 나타나요. 터치는 눌러서 열어요
        if (event.pointerType !== "touch") enter();
      }}
      onPointerLeave={(event) => {
        if (event.pointerType !== "touch") leave();
      }}
      onBlur={(event) => {
        // 포커스가 독 밖으로 나가면 파형으로 돌아가요. 독 안의 다른 버튼으로 옮기는 건 그대로 둬요
        if (!stageRef.current?.contains(event.relatedTarget as Node | null)) {
          scheduleClose();
        }
      }}
    >
      {isSeedSupported && isRecordingChipVisible && (
        <DockSeed
          isOpen={isOpen}
          analyser={analyser}
          isActive={!isRecordingPaused}
          elapsedTime={elapsedTime}
          splitTargets={splitTargets}
          onOpen={open}
        />
      )}

      {guide.shown && (
        <DockGuide kind={guide.shown.kind} tailOffset={guide.shown.tailOffset} isLeaving={guide.isLeaving} />
      )}

      <Bar
        ref={formRef}
        $isOpen={isOpen}
        $isInstant={isClosedByScreen}
        $isExpanded={isExpanded}
        $isRecording={isRecordingChipVisible && !isRecordingPaused}
        onSubmit={(event) => {
          if (canSubmit) burstAt(event.currentTarget.querySelector("[data-send]"));
          handleSubmit(event);
        }}
      >
        {isOpen && <DockParticles ref={particlesRef} barRef={formRef} />}

        <InputContainer $isHidden={!isExpanded}>
          {/* 펼친 입력창 앞의 말풍선도 접힌 독의 채팅 버튼과 같은 입자예요 */}
          <InputOrb aria-hidden="true">
            {isExpanded && (
              <ParticleOrb radius={DOCK_ORB_RADIUS} size={DOCK_ORB_CANVAS_SIZE} tone="neutral" shape="bubble" />
            )}
          </InputOrb>

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
          <Controls>
            {isMicVisible && (
              <>
                <ControlDivider aria-hidden="true" />
                <DockMicButton
                  isWaiting={isRecordingStarting}
                  onClick={() => {
                    traceFrom(formRef.current?.querySelector("[data-mic]"));
                    void handleMicClick();
                  }}
                />
              </>
            )}

            {isRecordingChipVisible && (
              <DockRecordingChip
                elapsedTime={elapsedTime}
                isPaused={isRecordingPaused}
                analyser={analyser}
                onOpen={isOtherTabRecording ? undefined : handleOpenRecording}
              />
            )}

            <SubmitButton
              type="submit"
              aria-label="보내기"
              disabled={!canSubmit}
              data-send
            >
              <ParticleIcon
                ref={sendIconRef}
                draw={drawSendIcon}
                size={16}
                isOnDark={false}
              >
                <SendIcon size={16} />
              </ParticleIcon>
            </SubmitButton>
          </Controls>
        ) : (
          /* 녹음 파형으로 접혀 있을 때는 자리만 차지하고 키보드·낭독기에서 닿지 않아요. 입력창은 밖에 있어 어디서든 타이핑은 그대로예요 */
          <CollapsedControls aria-hidden={!isOpen} inert={!isOpen}>
              <GuideTarget {...bindHoverGuide("chat")}>
                <DockChatButton isOrbVisible={isOpen} onClick={handleExpand} />
              </GuideTarget>

              {/* Figma처럼 구분선은 녹음 칩 앞에만 둬요. 대기 독은 말풍선과 마이크가 4px 사이로 붙어 있어요 */}
              {isRecordingChipVisible && <ControlDivider aria-hidden="true" />}

              {isMicVisible && (
                <RecordSlot data-dock-record {...bindHoverGuide("record")}>
                  <DockMicButton
                    isOrbVisible={isOpen}
                    isWaiting={isRecordingStarting}
                    onClick={() => {
                      traceFrom(formRef.current?.querySelector("[data-mic]"));
                      void handleMicClick();
                    }}
                  />
                </RecordSlot>
              )}

              {isRecordingChipVisible && (
                <RecordSlot data-dock-record>
                  <DockRecordingChip
                    elapsedTime={elapsedTime}
                    isPaused={isRecordingPaused}
                    analyser={isOpen ? analyser : null}
                    onOpen={isOtherTabRecording ? undefined : handleOpenRecording}
                    onStop={handleStopRecording}
                  />
                </RecordSlot>
              )}
          </CollapsedControls>
        )}
      </Bar>
    </Stage>
  );
}

/**
 * 파형과 독이 함께 놓이는 자리. 독과 같은 폭이고, 파형 캔버스는 이 위로 넘쳐 그려져요.
 * 포인터가 이 안에 있는 동안은 독이 열려 있어요.
 */
const Stage = styled.div`
  position: relative;
  display: flex;
  justify-content: center;
  width: 100%;
`;

/**
 * 접힘·펼침을 오가는 독 껍데기.
 *
 * 두 모양을 다른 요소로 두면 모션이 끊기므로, 한 요소의 폭만 바꿔 늘고 줄게 해요.
 * 녹음 칩이 있으면 접힌 폭이 내용만큼(`auto`)이라, `interpolate-size`로 `auto`와 길이 사이도 이어서 움직여요.
 * 이를 지원하지 않는 브라우저에서는 이때만 모션 없이 바로 바뀌어요.
 * 펼칠 때는 길게(0.44초), 접을 때는 조금 짧게(0.36초) 움직이고, 안의 내용은 폭이 어느 정도 벌어진 뒤 뒤따라 또렷해져요.
 */
const Bar = styled.form<{
  $isOpen: boolean;
  /** 탐색 화면을 떠나며 닫힐 때는 화면이 통째로 바뀌므로 접히는 모션 없이 바로 파형 뒤로 숨어요 */
  $isInstant: boolean;
  $isExpanded: boolean;
  $isRecording: boolean;
}>`
  position: relative;
  z-index: 1; /* 입자의 누르는 자리 위에 놓여요 */
  display: flex;
  align-items: flex-end; /* 여러 줄로 자라도 보내기 버튼은 아래에 남아요 */
  gap: ${({ $isExpanded }) =>
    $isExpanded ? "0.625rem" : "0.25rem"}; /* 10px : 4px */
  /* 펼치면 놓인 자리(대화 열과 같은 폭)를 다 채워요. 접혔을 때는 안내 글과 칸들만큼 내용 폭이에요 */
  width: ${({ $isExpanded }) => ($isExpanded ? "100%" : "auto")};
  min-height: ${DOCK_HEIGHT}; /* 여러 줄이면 이만큼에서부터 늘어나요 */
  padding: ${({ $isExpanded }) =>
    $isExpanded
      ? "0.75rem 0.75rem 0.75rem 1.25rem" /* 12px 12px 12px 20px */
      : "0 0.75rem"}; /* 12px */
  /* 접혔을 때만 동그란 알약이고, 펼친 뒤에는 여러 줄로 자라도 모서리가 30px로 고정돼요 */
  border-radius: ${({ $isExpanded }) =>
    $isExpanded ? "1.875rem" : "6.25rem"}; /* 30px : 100px */
  background-color: ${({ theme }) => theme.neutral[800]};
  /* 녹음 중엔 독 아래 그림자가 조금 더 깊어요. 파형이 녹음 화면과 같은 먹색이라 색 빛은 두지 않아요 */
  box-shadow: ${({ theme, $isRecording }) =>
    $isRecording ? `${theme.shadow03}, 0 10px 30px rgba(60, 59, 57, 0.14)` : theme.shadow03};
  interpolate-size: allow-keywords;
  transition-property: width, padding, gap, border-radius, box-shadow, opacity, transform;
  transition-duration: ${({ $isExpanded, $isInstant }) =>
    $isInstant ? "0s" : $isExpanded ? DOCK_EXPAND_DURATION : "0.36s"};
  transition-timing-function: ${DOCK_EASING};
  /* 녹음 파형으로 접혀 있을 땐 보이지 않게 작게 두었다가, 열리면 입자 뒤에서 자라나요 */
  opacity: ${({ $isOpen }) => ($isOpen ? 1 : 0)};
  transform: ${({ $isOpen }) => ($isOpen ? "none" : "scale(0.55)")};
  pointer-events: ${({ $isOpen }) => ($isOpen ? "auto" : "none")};

  /* 입자 캔버스가 맨 아래에 깔리고 나머지는 그 위에 놓여요 */
  & > :not(canvas) {
    position: relative;
    z-index: 1;
  }

  /* 말풍선은 제 모션이 따로 있으므로 한 줄에 놓이는 것들만 뒤따라 나타나게 해요 */
  & > div,
  & > button,
  & > span,
  & > span > button,
  & > span > span {
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

/** 펼친 입력창 앞 말풍선 자리. 24px 칸 가운데에 더 큰 캔버스를 겹쳐요 */
const InputOrb = styled.span`
  position: relative;
  display: flex;
  flex-shrink: 0;
  width: 1.5rem; /* 24px */
  height: 1.5rem;

  & > canvas {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
  }
`;

/** 접힌 독의 녹음 칸. 파형이 모일 자리를 재는 기준이고, 안의 버튼·칩처럼 세로 가운데에 놓여요 */
/** 올렸을 때 안내를 띄우려고 버튼을 감싸요. 독은 아래 정렬(여러 줄 입력)이라 버튼처럼 세로 가운데에 둬요 */
const GuideTarget = styled.span`
  display: flex;
  align-self: center;
`;

const RecordSlot = styled.span`
  display: flex;
  align-self: center;
  align-items: center;
`;

/** 접힌 독의 말풍선·녹음 칸 묶음. 배치에는 끼어들지 않고, 숨길 때 한꺼번에 숨기려고 둬요 */
const CollapsedControls = styled.span`
  display: contents;

  /* 녹음 파형으로 접혀 있을 때는 보이지 않아요. 열리면 입자가 모인 자리에 나타나요 */
  &[aria-hidden="true"] > * {
    opacity: 0;
  }
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
 * 입력창과 마이크, 접힌 독의 탐색 버튼과 녹음 칩 사이의 세로 구분선. 8×28 칸 가운데의 점선이에요.
 *
 * 입자와 같은 1.5px 점을 4px 간격으로 찍어요. 색은 어두운 독 위에서 보이는 Neutral/500이에요.
 */
const ControlDivider = styled.span`
  flex-shrink: 0;
  align-self: center;
  width: 0.5rem; /* 8px */
  height: 1.75rem; /* 28px */
  background: radial-gradient(
      circle,
      ${({ theme }) => theme.neutral[500]} 0.7px,
      transparent 0.9px
    )
    center / 1.5px 4px repeat-y;
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
