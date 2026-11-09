import ParticleOrb from "@composites/ParticleOrb";
import styled from "@emotion/styled";
import { useState } from "react";

/** 칩 속 파형의 구슬 반지름과 캔버스 가로·세로(px). 파형이 옆으로 길어 가로를 넓게 둬요 */
const ORB_RADIUS = 8;
const ORB_CANVAS_WIDTH = 36;
const ORB_CANVAS_HEIGHT = 24;

interface DockRecordingChipProps {
  /** 녹음한 시간. 예: `12:48` */
  elapsedTime: string;
  isPaused: boolean;
  /** 녹음 소리를 읽는 분석기. 파형이 목소리에 맞춰 커져요. 다른 탭의 녹음이면 없어 숨만 쉬어요 */
  analyser?: AnalyserNode | null;
  /** 넘기면 칩을 눌러 녹음 화면으로 가요. 넘기지 않으면 파형과 시간만 보여 주는 칩이에요 */
  onOpen?: () => void;
  /** 넘기면 칩 오른쪽에 중지 버튼을 둬요. 펼친 독은 옆에 보내기 버튼이 있어 두지 않아요 */
  onStop?: () => void;
}

/**
 * 녹음 화면이 아닌 곳에서 녹음이 이어지고 있을 때 마이크 자리에 놓이는 녹음 칩.
 *
 * 밝은 입자 파형과 녹음한 시간을 누르면 녹음 화면으로 가고(올리면 파형이 화살표로 모여 「가기」를 알려요), 접힌 독에서는 오른쪽 중지 버튼으로 녹음을 끝내요.
 * 파형은 오른쪽 끝이 지금 소리라 말하면 그 순간만 커져 왼쪽으로 흘러가고, 조용하거나 일시정지면 평평한 선이에요.
 * 다른 탭의 녹음이면 녹음 화면으로 갈 수 없어 칩을 눌러도 이동하지 않고, 중지 버튼으로 끝낼 수만 있어요(STT-R28).
 */
export default function DockRecordingChip({
  elapsedTime,
  isPaused,
  analyser = null,
  onOpen,
  onStop,
}: DockRecordingChipProps) {
  // 녹음 화면으로 갈 수 있는 칩에 올리면 파형이 화살표로 모여 「가기」를 알려요. 옆에 따로 화살표를 두지 않아요
  const [isArrow, setIsArrow] = useState(false);
  const status = (
    <>
      <OrbSlot role="img" aria-label={isPaused ? "일시정지" : "녹음 중"}>
        <ParticleOrb
          radius={ORB_RADIUS}
          size={ORB_CANVAS_HEIGHT}
          width={ORB_CANVAS_WIDTH}
          tone="neutral"
          shape={isArrow && onOpen ? "arrow" : "wave"}
          analyser={analyser}
          isActive={!isPaused}
        />
      </OrbSlot>
      <ElapsedTime>{elapsedTime}</ElapsedTime>
    </>
  );

  return (
    <Container $hasStop={onStop !== undefined}>
      {onOpen ? (
        <OpenButton
          type="button"
          aria-label="녹음 화면으로 이동"
          onClick={onOpen}
          onPointerEnter={() => setIsArrow(true)}
          onPointerLeave={() => setIsArrow(false)}
          onFocus={() => setIsArrow(true)}
          onBlur={() => setIsArrow(false)}
        >
          {status}
        </OpenButton>
      ) : (
        status
      )}

      {onStop && (
        <StopButton type="button" aria-label="녹음 끝내기" onClick={onStop}>
          <StopMark />
        </StopButton>
      )}
    </Container>
  );
}

/** 중지 버튼이 있으면 오른쪽 여백을 4px로 줄여 32px 버튼이 칩 끝에 붙어요. */
const Container = styled.div<{ $hasStop: boolean }>`
  display: flex;
  flex-shrink: 0;
  align-self: center;
  align-items: center;
  gap: 0.5rem; /* 8px */
  padding: ${({ $hasStop }) =>
    $hasStop
      ? "0.25rem 0.25rem 0.25rem 0.75rem" /* 4px 4px 4px 12px */
      : "0.25rem 0.75rem 0.25rem 0.75rem"}; /* 4px 12px 4px 12px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[700]};
`;

const OpenButton = styled.button`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  color: ${({ theme }) => theme.neutral[0]};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** 녹음 중이면 밝은 파형이 목소리에 맞춰 오른쪽부터 커지고, 조용하거나 일시정지면 평평한 선으로 쉬어요. */
const OrbSlot = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 1.5rem; /* 24px */
`;

const ElapsedTime = styled.span`
  color: ${({ theme }) => theme.neutral[0]};
  font-variant-numeric: tabular-nums; /* 숫자가 바뀌어도 폭이 흔들리지 않아요 */
  white-space: nowrap;
  ${({ theme }) => theme.text.label01};
`;

const StopButton = styled.button`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2rem; /* 32px */
  height: 2rem;
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[800]};

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

/** 녹음 끝내기(■). 모서리가 둥근 11px 사각형이에요. */
const StopMark = styled.span`
  width: 0.6875rem; /* 11px */
  height: 0.6875rem;
  border-radius: 0.15625rem; /* 2.5px */
  background-color: currentColor;
`;
