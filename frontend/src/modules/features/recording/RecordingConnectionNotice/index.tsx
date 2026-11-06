import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import useRecordingState from "@hooks/domain/recording/useRecordingState";

import AlertIcon from "@/assets/icons/alert.svg";

import { CONNECTION_NOTICE_COPY } from "./constants/connectionNoticeCopy";

/**
 * 녹음 화면의 연결 끊김 줄(STT-R53). 시안 `Recorder/ConnectionNotice`예요.
 *
 * 연결이 끊긴 동안만 보이고 돌아오면 사라져요. 끊겨도 녹음은 계속돼, 소리를 잃지 않는다는 것을 먼저 알려요.
 * 브라우저가 조각 보관을 거절했으면 탭을 닫을 때 소리를 잃을 수 있다고 알려요.
 * 끊긴 채 녹음을 끝내면 남은 소리를 올릴 때까지 기다린다고 바꿔 보여요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-42635 시안
 */
export default function RecordingConnectionNotice() {
  const { isConnectionLost, isChunkArchiveFailed, isEnding } =
    useRecordingState();

  if (!isConnectionLost) return null;

  const getCopyKey = () => {
    if (isEnding) return "ending";

    return isChunkArchiveFailed ? "archiveFailed" : "lost";
  };
  const { title, description } = CONNECTION_NOTICE_COPY[getCopyKey()];

  return (
    <Container role="status">
      <IconWrap aria-hidden="true">
        <AlertIcon size={18} />
      </IconWrap>
      <Text>
        <Title>{title}</Title>
        <Description>{description}</Description>
      </Text>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  min-width: 0;
  padding: 0.75rem 1rem; /* 12px 16px */
  border: 1px solid ${({ theme }) => theme.sub.caution[700]};
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[50]};

  ${MEDIA_QUERY.mobile} {
    padding: 1rem; /* 16px */
  }
`;

const IconWrap = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.5rem; /* 40px */
  height: 2.5rem;
  border-radius: 50%;
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.sub.caution[700]};
`;

const Text = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const Title = styled.p`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

const Description = styled.p`
  color: ${({ theme }) => theme.neutral[600]};
  overflow-wrap: break-word;
  ${({ theme }) => theme.text.caption02};
`;
