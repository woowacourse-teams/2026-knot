import styled from "@emotion/styled";
import type { Ref } from "react";

import StartLink from "./StartLink";
import { DesktopOnly, Lead, Phrase } from "./ScenePanel";
import { focusRing, interactive, mobile, sceneFade, visuallyHidden } from "./SceneLayer";

interface IntroSceneProps {
  isOn: boolean;

  /** 스크롤 안내 마우스가 그려질 자리. 엔진이 이 자리에 입자로 마우스와 휠 점을 그려요. */
  scrollCueRef: Ref<HTMLDivElement>;

  /** 스크롤 안내를 눌렀을 때. 다음 장면으로 넘겨요. */
  onNext: () => void;
}

/**
 * 첫 장면. 워드마크 아래에 슬로건과 서비스 설명, 로그인 화면으로 가는 「시작하기」를 함께 보여 줘요.
 *
 * 긴 도입 연출로 설명과 시작 자리를 가리지 않도록, 첫 화면에서 바로 모두 보이게 둡니다.
 * 화면 아래에는 입자로 그린 마우스의 휠 점이 아래로 굴러가며 스크롤하면 된다는 걸 알려 주고, 누르면 다음 장면으로 넘어가요.
 */
export default function IntroScene({ isOn, scrollCueRef, onNext }: IntroSceneProps) {
  return (
    <>
      <Container $isOn={isOn} aria-hidden={!isOn} inert={!isOn}>
        <ServiceName>knot</ServiceName>
        <Slogan>팀의 결정을 모두의 기억으로</Slogan>
        <Lead>
          <Phrase>회의를 녹음하면</Phrase> <Phrase>결정만 골라</Phrase> <Phrase>주제별 문서로 정리해요.</Phrase>
          <DesktopOnly>
            <br />
          </DesktopOnly>{" "}
          <Phrase>나중에 물어보면</Phrase> <Phrase>근거와 함께 다시 찾아 줘요.</Phrase>
        </Lead>
        <StartLink isOn={isOn} />
      </Container>
      <ScrollCue type="button" $isOn={isOn} aria-hidden={!isOn} inert={!isOn} onClick={onNext}>
        스크롤하면 한 장씩 넘어가요
        <MouseSlot ref={scrollCueRef} aria-hidden />
      </ScrollCue>
    </>
  );
}

const Container = styled.section<{ $isOn: boolean }>`
  ${({ $isOn }) => sceneFade({ isOn: $isOn })};
  position: absolute;
  top: 46%;
  right: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.875rem; /* 14px */
  padding: 0 1.5rem;
  text-align: center;

  ${mobile} {
    top: 29%;
  }
`;

const ServiceName = styled.h1`
  ${visuallyHidden};
`;

const Slogan = styled.p`
  ${({ theme }) => theme.text.title02};
  color: ${({ theme }) => theme.neutral[900]};

  ${mobile} {
    font-size: 1.75rem; /* 28px */
  }
`;

/** 화면 맨 아래 가운데의 스크롤 안내. 글자 아래 빈자리에 캔버스 입자가 마우스를 그려요. */
const ScrollCue = styled.button<{ $isOn: boolean }>`
  ${({ $isOn }) => sceneFade({ isOn: $isOn })};
  ${({ $isOn }) => interactive({ isOn: $isOn })};
  position: absolute;
  bottom: 1.5rem; /* 24px */
  left: 50%;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem; /* 4px */
  padding: 0.5rem 1rem 0; /* 8px 16px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
  /* 화면 가운데에서 시작하는 자리라 너비가 화면의 절반으로 묶여, 모바일에서 두 줄로 꺾이지 않게 해요 */
  white-space: nowrap;
  transform: translateX(-50%);
  ${({ theme }) => focusRing(theme)};

  &:hover {
    color: ${({ theme }) => theme.neutral[800]};
  }

  ${mobile} {
    bottom: 1rem; /* 16px */
  }
`;

const MouseSlot = styled.div`
  width: 2rem; /* 32px */
  height: 2.75rem; /* 44px */
`;
