import { MEDIA_QUERY } from "@constants/breakpoint";
import type { Theme } from "@emotion/react";
import styled from "@emotion/styled";
import type { ReactNode } from "react";

/** 소리 이름 옆의 상태 표시. 녹음 중은 빨강, 확인이 필요한 알림은 주의색, 나머지는 회색이에요 */
export interface SoundSourceStatus {
  /** 없으면 이름 옆 표시 없이 줄의 색만 바꿔요 */
  label?: string;
  tone: "recording" | "paused" | "caution" | "disabled";
}

interface SoundSourceCardProps {
  icon: ReactNode;
  title: string;
  /** 없으면 이름만 보여요 */
  status: SoundSourceStatus | null;
  description: string;
  /** 오른쪽 끝에 둘 버튼 */
  action?: ReactNode;
}

/**
 * 녹음하는 소리 하나를 보여 주는 줄. Figma `Recorder/SoundSource`의 `Source`예요.
 *
 * 상태가 `caution`이면 테두리를 주의색으로, `disabled`면 줄 전체를 흐리게 그려요.
 * 상태와 설명이 바뀌면 화면 낭독기가 읽어요.
 */
export default function SoundSourceCard({
  icon,
  title,
  status,
  description,
  action,
}: SoundSourceCardProps) {
  const tone = status?.tone;

  return (
    <Container $tone={tone}>
      <IconWrap aria-hidden $tone={tone}>
        {icon}
      </IconWrap>
      <Text role="status">
        <TitleRow>
          <Title $tone={tone}>{title}</Title>
          {status?.label && (
            <Status $tone={status.tone}>
              <Dot aria-hidden $tone={status.tone} />
              {status.label}
            </Status>
          )}
        </TitleRow>
        <Description $tone={tone}>{description}</Description>
      </Text>
      {action}
    </Container>
  );
}

type Tone = SoundSourceStatus["tone"] | undefined;

const Container = styled.div<{ $tone: Tone }>`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  min-width: 0;
  padding: 0.75rem 1rem; /* 12px 16px */
  border: 1px solid
    ${({ theme, $tone }) =>
      $tone === "caution" ? theme.sub.caution[700] : theme.neutral[200]};
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme, $tone }) =>
    $tone === "disabled" ? theme.neutral[100] : theme.neutral[50]};

  ${MEDIA_QUERY.mobile} {
    padding: 1rem; /* 16px */
  }

  & > button {
    flex-shrink: 0;
  }
`;

const IconWrap = styled.span<{ $tone: Tone }>`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.5rem; /* 40px */
  height: 2.5rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[500]};

  & > svg {
    opacity: ${({ $tone }) => ($tone === "disabled" ? 0.5 : 1)};
  }
`;

const Text = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const TitleRow = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  column-gap: 0.625rem; /* 10px */
`;

const Title = styled.span<{ $tone: Tone }>`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme, $tone }) =>
    $tone === "disabled" ? theme.neutral[500] : theme.neutral[900]};
`;

/** 상태별 점과 글자 색 */
const statusColor = (theme: Theme, tone: SoundSourceStatus["tone"]) =>
  ({
    recording: { dot: theme.sub.warning[600], text: theme.sub.warning[700] },
    paused: { dot: theme.neutral[400], text: theme.neutral[600] },
    caution: { dot: theme.sub.caution[700], text: theme.sub.caution[700] },
    disabled: { dot: theme.neutral[400], text: theme.neutral[500] },
  })[tone];

const Status = styled.span<{ $tone: SoundSourceStatus["tone"] }>`
  display: inline-flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme, $tone }) => statusColor(theme, $tone).text};
`;

const Dot = styled.span<{ $tone: SoundSourceStatus["tone"] }>`
  flex-shrink: 0;
  width: 0.375rem; /* 6px */
  height: 0.375rem;
  border-radius: 50%;
  background-color: ${({ theme, $tone }) => statusColor(theme, $tone).dot};
`;

const Description = styled.p<{ $tone: Tone }>`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme, $tone }) =>
    $tone === "disabled" ? theme.neutral[500] : theme.neutral[600]};
  overflow-wrap: break-word;
`;
