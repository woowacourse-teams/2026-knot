import styled from "@emotion/styled";
import IllustratedMessageLayout from "@primitives/layout/IllustratedMessageLayout";
import Button from "@primitives/ui/Button";

import DocumentFailedIllustration from "@/assets/illustrations/documentFailed.svg";

interface RecordingDocumentLoadFailedProps {
  /** `다시 시도`를 눌렀을 때 실행할 동작 */
  onRetry: () => void;
}

/**
 * 녹음 문서를 보여 주지 못할 때의 안내. 문서 보기의 「문서/불러오기 실패」와 같은 모양이에요.
 *
 * 네트워크 · 서버 문제뿐 아니라 없는 녹음 · 다른 워크스페이스의 녹음 · 잘못된 주소에도 이 화면 하나를 써요.
 * 녹음 문서용 시안이 없어 문서 보기의 실패 화면을 따르고 문구의 「문서」만 「녹음」으로 바꿨어요.
 * 낭독기가 바로 읽도록 `role="alert"`를 붙였어요.
 */
export default function RecordingDocumentLoadFailed({
  onRetry,
}: RecordingDocumentLoadFailedProps) {
  return (
    <Root role="alert">
      <IllustratedMessageLayout
        illustration={<Illustration aria-hidden="true" />}
        title={<Title>녹음을 불러오지 못했어요</Title>}
        description={<Description>잠시 후 다시 시도해 주세요.</Description>}
        button={
          <Button size="sm" onClick={onRetry}>
            다시 시도
          </Button>
        }
      />
    </Root>
  );
}

/** 피그마 State/DocNotCreated: 묶음 폭 518px, 화면 세로 가운데 */
const Root = styled.div`
  display: flex;
  flex-direction: column;
  justify-content: center;
  width: 100%;
  max-width: 32.375rem; /* 518px */
  margin: 0 auto;
`;

const Illustration = styled(DocumentFailedIllustration)`
  width: 3rem; /* 48px */
  height: 3rem;
  color: ${({ theme }) => theme.primary};
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.primary};
`;

const Description = styled.p`
  ${({ theme }) => theme.text.body02};
  color: ${({ theme }) => theme.neutral[700]};
`;
