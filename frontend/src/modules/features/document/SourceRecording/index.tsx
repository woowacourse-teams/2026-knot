import useRecordingDocumentQuery from "@api/queries/useRecordingDocumentQuery";
import styled from "@emotion/styled";
import RelatedCard from "@primitives/ui/RelatedCard";
import Skeleton from "@primitives/ui/Skeleton";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { useId } from "react";

import MicIcon from "@/assets/icons/mic.svg";

/** 녹음 제목을 받지 못했을 때 카드 제목 */
const FALLBACK_TITLE = "녹음 보기";
const DESCRIPTION = "회의 전체 요약, 원문, 녹음 음성을 볼 수 있어요.";

interface SourceRecordingProps {
  workspaceId: number;
  /** 이 문서를 만든 녹음 ID. 문서 상세의 `recordingSessionId`예요 */
  recordingId: number;
}

/**
 * 문서 보기의 「이 문서가 나온 녹음」(DOC-R25). 이 문서를 만든 녹음을 카드 하나로 보여 주고, 누르면 그 녹음의 녹음 문서로 가요.
 * 카드는 녹음 문서의 「이 회의에서 나온 문서」 카드와 같은 모양이에요.
 *
 * 녹음 제목은 녹음 문서 조회로 받아요. 받는 동안은 제목 자리만 스켈레톤으로 채우고, 받지 못하면 「녹음 보기」로 보여 줘요.
 * 어느 때든 녹음 ID는 이미 있으므로 카드를 누르면 녹음 문서로 가요. 제목을 받지 못해도 문서 본문에는 영향이 없어요.
 */
export default function SourceRecording({
  workspaceId,
  recordingId,
}: SourceRecordingProps) {
  const titleId = useId();
  const { data: recordingDocument, isPending } = useRecordingDocumentQuery({
    workspaceId,
    recordingId,
  });

  const renderTitle = () => {
    if (isPending) {
      // 피그마 카드 상태=불러오는 중: 제목 자리 140px × 14px
      return <Skeleton width={8.75} height={0.875} radius={0.25} />;
    }

    return recordingDocument?.title ?? FALLBACK_TITLE;
  };

  return (
    <Root aria-labelledby={titleId}>
      <Title id={titleId}>이 문서가 나온 녹음</Title>
      <CardColumn>
        <RelatedCard
          href={getRouterPath({
            routeKey: "RECORDING_DOCUMENT",
            params: {
              workspaceId: String(workspaceId),
              recordingId: String(recordingId),
            },
          })}
          icon={<MicIcon size={16} aria-hidden />}
          title={renderTitle()}
          description={DESCRIPTION}
        />
      </CardColumn>
    </Root>
  );
}

/** 피그마 Source/녹음: 본문과 사이 40px(문서 열 간격 24px에 16px을 더해요), 머리와 카드 사이 16px. 녹음 문서의 「이 회의에서 나온 문서」와 같아요 */
const Root = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  padding-top: 1rem; /* 16px */
`;

const Title = styled.h3`
  ${({ theme }) => theme.text.heading04};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 「이 회의에서 나온 문서」의 두 열 격자와 같은 칸 폭. 넓은 화면에서는 한 칸, 좁은 화면에서는 본문 폭을 채워요 */
const CardColumn = styled.div`
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(17.5rem, 100%), 1fr));
  gap: 1rem 1.25rem; /* 16px 20px */
`;
