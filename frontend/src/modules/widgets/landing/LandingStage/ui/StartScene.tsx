import styled from "@emotion/styled";

import StartLink from "./StartLink";
import { Lead } from "./ScenePanel";
import { mobile, sceneFade } from "./SceneLayer";

interface StartSceneProps {
  isOn: boolean;
}

/** 마지막 장면. 입자가 새 심볼로 모이고, 그 아래 「시작하기」로 로그인 화면에 가요. */
export default function StartScene({ isOn }: StartSceneProps) {
  return (
    <Container $isOn={isOn} aria-hidden={!isOn} inert={!isOn}>
      <Title>
        팀의 결정을
        <MobileBreak /> 모두의 기억으로.
      </Title>
      <Lead>흩어지던 말은 문서로, 문서는 모두의 기억으로.</Lead>
      <StartLink isOn={isOn} />
    </Container>
  );
}

const Container = styled.section<{ $isOn: boolean }>`
  ${({ $isOn }) => sceneFade({ isOn: $isOn })};
  position: absolute;
  top: 48%;
  right: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.875rem; /* 14px */
  padding: 0 1.5rem;
  text-align: center;

  ${mobile} {
    top: 38%;
  }
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.title02};
  color: ${({ theme }) => theme.neutral[900]};

  ${mobile} {
    font-size: 1.75rem; /* 28px */
  }
`;

const MobileBreak = styled.br`
  display: none;

  ${mobile} {
    display: inline;
  }
`;
