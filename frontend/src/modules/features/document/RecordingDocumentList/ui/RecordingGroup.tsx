import styled from "@emotion/styled";
import { type ReactNode, useId } from "react";

import RecordingRow from "./RecordingRow";

interface RecordingGroupProps {
  /** 머리 행을 누르면 갈 녹음 문서 주소 */
  recordingHref: string;
  /** 서버가 만든 표시 제목 */
  title: string;
  /** 녹음 시작 시각(ISO 8601) */
  startedAt: string;
  /** 녹음 길이(초) */
  recordingDurationSeconds: number;
  /** 묶음에 담을 문서 행(`RecordingDocumentRow`) */
  children: ReactNode;
}

/**
 * 「녹음별」 탭의 녹음 묶음 하나. 카드 하나에 녹음의 머리 행과 그 녹음에서 나온 문서 행을 모두 펼쳐 보여줘요.
 *
 * 문서가 많아도 접거나 나누지 않아요(DOC-R23).
 */
export default function RecordingGroup({
  recordingHref,
  title,
  startedAt,
  recordingDurationSeconds,
  children,
}: RecordingGroupProps) {
  const titleId = useId();

  return (
    <Card aria-labelledby={titleId}>
      <RecordingRow
        href={recordingHref}
        titleId={titleId}
        title={title}
        startedAt={startedAt}
        recordingDurationSeconds={recordingDurationSeconds}
      />
      <Documents>{children}</Documents>
    </Card>
  );
}

/** 피그마 녹음 카드: 흰 바탕, 모서리 20px, Shadow02. 행의 모서리가 카드 밖으로 나가지 않게 잘라요 */
const Card = styled.section`
  overflow: hidden;
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

const Documents = styled.ul`
  display: flex;
  flex-direction: column;
`;
