import {
  HTTP_ERROR_TYPE,
  type HttpErrorType,
  isHttpError,
} from "@api/httpClient/error";
import styled from "@emotion/styled";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import useAnalyticsScreenTarget from "@hooks/domain/document/useAnalyticsScreenTarget";
import Chip from "@primitives/ui/Chip";
import Divider from "@primitives/ui/Divider";
import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";

import { useTitleId } from "./model/useTitleId";
import { useTranscriptSource } from "./model/useTranscriptSource";
import TranscriptLine from "./ui/TranscriptLine";
import TranscriptLoadFailed from "./ui/TranscriptLoadFailed";
import TranscriptCopyButton from "./ui/TranscriptCopyButton";
import TranscriptSkeleton from "./ui/TranscriptSkeleton";
import { formatTranscriptText } from "./utils/formatTranscriptText";

/** 원문이 없어졌거나 볼 수 없게 된 실패. 경로 형식 오류(400) · 워크스페이스 멤버 아님(403) · 문서나 원문 없음(404) */
const UNAVAILABLE_TYPES: HttpErrorType[] = [
  HTTP_ERROR_TYPE.badRequest,
  HTTP_ERROR_TYPE.forbidden,
  HTTP_ERROR_TYPE.notFound,
];

const isUnavailableError = (error: unknown) =>
  isHttpError(error) && UNAVAILABLE_TYPES.includes(error.type);

/**
 * 원문 패널. 전사를 말한 시각 · 말한 사람 · 문장으로 한 줄씩 보여줘요.
 *
 * 문서 보기에서는 주소의 문서를 만든 원본 전사를, 녹음 문서에서는 주소의 녹음의 전사 전체를 보여줘요(DOC-R3 · STT-R39).
 * 두 화면은 날짜 · 녹음 길이를 어디서 가져오는지와 꼬리말만 달라요(`useTranscriptSource`).
 *
 * 화면의 오른쪽에 놓여요. 패널을 여닫는 버튼은 GNB에 있어서, 패널 안에는 닫기 버튼을 두지 않아요(DOC-R3).
 * 발화가 많으면 패널 전체가 아니라 발화 목록만 세로로 스크롤해요(DOC-R4).
 * 머리의 「원문」 오른쪽 끝에는 보이는 발화 줄을 그대로 복사하는 버튼을 둬요. 복사에 성공하면 원문 복사를 사용 기록(`content_copied`)으로 보내요.
 * 스크롤 기록은 패널을 닫았다 열어도 한 번만 세도록 패널을 놓는 레이아웃이 보내요.
 *
 * 원문을 받지 못한 동안에는 상태 화면이 패널 전체를 대신 그려요.
 * - 불러오는 중: `TranscriptSkeleton`. 로그인이 풀려(401) 로그인 화면으로 보내는 동안에도 이 화면이에요.
 * - 불러오기 실패: `TranscriptLoadFailed`. 400 · 403 · 404는 원문이 없어졌거나 볼 수 없게 된 것이라, 이미 받은 원문이 있어도 이 화면이에요.
 *   네트워크 · 5xx는 받은 원문이 없을 때만 이 화면이고, 받은 원문이 있으면 계속 보여줘요.
 *
 * 주소의 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 원문을 불러오지 못했다고 알려요.
 *
 * 날짜는 원문 응답에 없어 본문 섹션의 조회(문서 상세 · 녹음 문서)에서 가져와요. 같은 쿼리 키라 캐시를 함께 써요.
 * 그 조회를 받지 못했으면 날짜 없이 원문을 보여줘요.
 */
export default function TranscriptPanel() {
  const { titleId } = useTitleId();
  const {
    segments,
    date,
    durationSeconds,
    footerNotice,
    error,
    isError,
    refetch,
  } = useTranscriptSource();
  const { isUnauthorized } = useRedirectToLoginOnUnauthorized({ error });
  const { analyticsScreenTarget } = useAnalyticsScreenTarget();

  // 화면을 새로고침하지 않고 원문만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetry = () => {
    refetch();
  };

  const handleTranscriptCopied = () => {
    captureAnalyticsEvent({
      event: "content_copied",
      properties: { content: "transcript", ...analyticsScreenTarget },
    });
  };

  // 400 · 403 · 404는 받은 원문이 있어도 실패 안내를 보여줘요. 그 밖의 실패는 받은 원문이 없을 때만 보여주고,
  // 로그인 화면으로 보내는 중(401)이면 가는 동안 안내가 잠깐 보이지 않게 빼요
  const shouldShowLoadFailed =
    isUnavailableError(error) ||
    (segments === undefined && isError && !isUnauthorized);

  if (shouldShowLoadFailed) {
    return <TranscriptLoadFailed onRetry={handleRetry} />;
  }

  if (segments === undefined) {
    return <TranscriptSkeleton />;
  }

  return (
    <Container aria-labelledby={titleId}>
      <Header>
        <Title id={titleId}>원문</Title>
        <TranscriptCopyButton
          text={formatTranscriptText(segments)}
          onCopied={handleTranscriptCopied}
        />
      </Header>
      <Meta>
        {date !== undefined && (
          <CreatedDate dateTime={date}>{formatDate(date)}</CreatedDate>
        )}
        {durationSeconds !== undefined && (
          <Chip>{formatDurationFromSeconds(durationSeconds)}</Chip>
        )}
      </Meta>
      <Divider />
      <LineList>
        {segments.map(({ startMillis, speakerNumber, text }, index) => (
          <TranscriptLine
            // 구간에는 id가 없고 같은 시각에 시작하는 구간이 있을 수 있어, 서버가 준 순서를 함께 써요
            key={`${startMillis}-${index}`}
            startMillis={startMillis}
            speakerNumber={speakerNumber}
            text={text}
          />
        ))}
      </LineList>
      <FooterNotice>{footerNotice}</FooterNotice>
    </Container>
  );
}

/**
 * 피그마 Panel/Transcript: 안쪽 여백 24px, 묶음 사이 16px, 모서리 20px, Shadow03.
 * 폭(400px)과 높이의 한계는 패널을 놓는 자리가 정하고, 여기서는 그 자리를 넘지 않게만 해요
 */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  max-height: 100%;
  padding: 1.5rem; /* 24px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow03};
`;

/** 피그마 Header: 왼쪽에 제목, 오른쪽 끝에 복사 버튼. 둘 사이 8px, 세로 가운데 */
const Header = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem; /* 8px */
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const CreatedDate = styled.time`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 피그마 Transcript: 발화 줄 사이 10px. 패널이 자리보다 길어지면 이 목록만 스크롤해요 */
const LineList = styled.ol`
  display: flex;
  flex-direction: column;
  gap: 0.625rem; /* 10px */
  min-height: 0;
  overflow-y: auto;
`;

const FooterNotice = styled.p`
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme }) => theme.neutral[500]};
`;
