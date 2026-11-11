import styled from "@emotion/styled";
import useKeyDown from "@hooks/common/useKeyDown";
import { useSearchTutorialStore } from "@store/searchTutorialStore";
import { createPortal } from "react-dom";

import {
  COACH_COPY,
  DOCUMENT_COACH_TITLE,
  FIRST_STEP_DISMISS_LABEL,
  SEARCH_TUTORIAL_TARGET,
  type SearchTutorialTarget,
} from "./constants/searchTutorial";
import { useSearchTutorialFlow } from "./model/useSearchTutorialFlow";
import { useSearchTutorialScreen } from "./model/useSearchTutorialScreen";
import { useSearchTutorialTrigger } from "./model/useSearchTutorialTrigger";
import { useSpotlightRects, type SpotlightRect } from "./model/useSpotlightRects";
import CoachCard, { CoachButton } from "./ui/CoachCard";
import QuestionKindCards from "./ui/QuestionKindCards";
import TutorialBar from "./ui/TutorialBar";
import {
  placeAboveStart,
  placeBelowCenter,
  placeBelowStart,
  toDimClipPath,
  toHole,
  unionRect,
  type SpotlightHole,
} from "./utils/spotlightLayout";

/** 독 입력창과 그 위 질문 종류 카드 사이 간격(px) */
const QUESTION_KINDS_GAP = 12;

const getTargets = (step: number | null, isChatScreen: boolean): SearchTutorialTarget[] => {
  switch (step) {
    case 1:
      return [
        SEARCH_TUTORIAL_TARGET.searchTab,
        isChatScreen ? SEARCH_TUTORIAL_TARGET.dockInput : SEARCH_TUTORIAL_TARGET.dockChat,
      ];
    case 2:
      return [SEARCH_TUTORIAL_TARGET.dockInput];
    case 3:
      return [SEARCH_TUTORIAL_TARGET.conversation];
    case 4:
      return [SEARCH_TUTORIAL_TARGET.dockInput, SEARCH_TUTORIAL_TARGET.questionKinds];
    default:
      return [];
  }
};

/**
 * 탐색 튜토리얼. 탐색이라는 기능이 있고, 회의가 끝난 뒤 정한 것과 못 정한 것을 물어볼 때 쓴다는 것을 직접 겪게 해요.
 *
 * 완료된 회의 문서가 있는 워크스페이스에서 멤버마다 처음 한 번 떠요(`useSearchTutorialTrigger`). 단계는 넷이에요.
 * 1. 화면을 어둡게 하고 GNB 탐색 탭과 독 채팅 버튼(탐색 화면에서는 입력창)만 밝혀요. 밝힌 곳이나 「보여 주세요」를 누르면 탐색으로 가요.
 * 2. 예시 질문을 독 입력창에 한 글자씩 넣고 자동으로 보내요.
 * 3. 예시 답변을 대화 칸에 쓰듯 보여 준 뒤, 대화를 밝혀 결론과 근거 문서를 설명해요.
 * 4. 질문 종류 카드 3개와 입력창을 밝혀 직접 묻게 해요. 카드를 누르거나 직접 보내면 실제 질문이 되고 끝나요.
 *
 * 예시 대화는 늘 같은 목 데이터라 서버에 보내지 않고 대화 목록에도 남지 않아요.
 * 밝힌 곳은 딤드에 뚫린 구멍이라, 4단계의 입력창은 그대로 눌러 쓸 수 있어요.
 * ESC는 1단계에서 보조 버튼(다음에 볼게요, 닫기)과, 2~4단계에서 건너뛰기와 같아요.
 */
export default function SearchTutorial() {
  useSearchTutorialTrigger();
  const { step, proceedFromFirst, dismissFirst, goToNext, skip, finish, chooseQuestionKind } =
    useSearchTutorialFlow();
  const variant = useSearchTutorialStore((state) => state.variant);
  const isAnswerShown = useSearchTutorialStore((state) => state.isAnswerShown);
  const { isChatScreen } = useSearchTutorialScreen();
  const { rects, viewport } = useSpotlightRects(getTargets(step, isChatScreen), step !== null);

  const isAgain = variant === "again";

  useKeyDown({
    key: "Escape",
    isEnabled: step !== null,
    onKeyDown: () => (step === 1 ? dismissFirst(isAgain) : skip()),
  });

  if (step === null) return null;

  const searchTab = rects[SEARCH_TUTORIAL_TARGET.searchTab];
  const dockChat = rects[SEARCH_TUTORIAL_TARGET.dockChat];
  const dockInput = rects[SEARCH_TUTORIAL_TARGET.dockInput];
  const conversation = rects[SEARCH_TUTORIAL_TARGET.conversation];
  const questionKinds = rects[SEARCH_TUTORIAL_TARGET.questionKinds];

  const holes: SpotlightHole[] = [];
  const addHole = (rect: SpotlightRect | undefined) => {
    if (rect) holes.push(toHole(rect));
  };

  if (step === 1) {
    addHole(searchTab);
    addHole(isChatScreen ? dockInput : dockChat);
  }
  if (step === 2) addHole(dockInput);
  if (step === 3) addHole(conversation);
  if (step === 4 && dockInput) addHole(questionKinds ? unionRect(questionKinds, dockInput) : dockInput);

  const copy = COACH_COPY[step];

  const renderCoach = () => {
    if (step === 1) {
      if (!searchTab) return null;

      return (
        <CoachCard
          step={1}
          title={variant === "document" ? DOCUMENT_COACH_TITLE : copy.title}
          description={copy.description}
          style={placeBelowCenter(toHole(searchTab), viewport.width)}
          actions={
            <>
              <CoachButton type="button" $variant="text" onClick={() => dismissFirst(isAgain)}>
                {FIRST_STEP_DISMISS_LABEL[variant]}
              </CoachButton>
              <CoachButton type="button" $variant="primary" onClick={proceedFromFirst}>
                보여 주세요
              </CoachButton>
            </>
          }
        />
      );
    }

    if (step === 2) {
      if (!dockInput) return null;

      return (
        <CoachCard
          step={2}
          title={copy.title}
          description={copy.description}
          footnote="질문을 입력하는 중…"
          style={placeAboveStart(toHole(dockInput), viewport)}
        />
      );
    }

    if (step === 3) {
      if (!conversation || !isAnswerShown) return null;

      return (
        <CoachCard
          step={3}
          title={copy.title}
          description={copy.description}
          style={placeBelowStart(toHole(conversation), viewport)}
          actions={
            <CoachButton type="button" $variant="primary" onClick={() => goToNext(4)}>
              다음
            </CoachButton>
          }
        />
      );
    }

    if (!questionKinds) return null;

    return (
      <CoachCard
        step={4}
        title={copy.title}
        description={copy.description}
        style={placeAboveStart(toHole(questionKinds), viewport)}
        actions={
          <CoachButton type="button" $variant="primary" onClick={finish}>
            끝내기
          </CoachButton>
        }
      />
    );
  };

  // 1단계의 밝힌 곳은 누르면 바로 2단계로 가요. 아래 화면의 버튼 대신 이 자리가 눌림을 받아요
  const firstStepTargets = step === 1 ? holes : [];

  return createPortal(
    <Overlay data-testid="search-tutorial">
      <Dim style={{ clipPath: toDimClipPath(viewport, holes) }} aria-hidden="true" />

      {holes.map((hole, index) => (
        <Spotlight
          key={index}
          aria-hidden="true"
          style={{ top: hole.top, left: hole.left, width: hole.width, height: hole.height, borderRadius: hole.radius }}
        />
      ))}

      {firstStepTargets.map((hole, index) => (
        <HoleButton
          key={index}
          type="button"
          aria-label="탐색 열기"
          onClick={proceedFromFirst}
          style={{ top: hole.top, left: hole.left, width: hole.width, height: hole.height, borderRadius: hole.radius }}
        />
      ))}

      {step === 1 && !isChatScreen && dockChat && (
        <Hint style={{ left: dockChat.left + dockChat.width / 2, top: dockChat.top - 48 }}>
          독의 채팅 버튼으로도 열 수 있어요
        </Hint>
      )}

      {step === 4 && dockInput && (
        <QuestionKindCards
          style={{
            left: dockInput.left,
            width: dockInput.width,
            bottom: viewport.height - dockInput.top + QUESTION_KINDS_GAP,
          }}
          onChoose={(kind, question) => void chooseQuestionKind(kind, question)}
        />
      )}

      {renderCoach()}

      {step >= 2 && <TutorialBar onSkip={skip} />}
    </Overlay>,
    document.body,
  );
}

/** 화면 전체를 덮지만 눌림은 딤드와 그 위 요소만 받아요. 밝힌 구멍은 아래 화면으로 눌림이 지나가요 */
const Overlay = styled.div`
  position: fixed;
  inset: 0;
  z-index: 35; /* 모달 배경 막(30) 위, 토스트(40) 아래 */
  pointer-events: none;
`;

const Dim = styled.div`
  position: absolute;
  inset: 0;
  background-color: rgba(20, 19, 18, 0.58);
  pointer-events: auto;
`;

const Spotlight = styled.div`
  position: absolute;
  border: 3px solid ${({ theme }) => theme.neutral[0]};
  box-shadow: 0 0 18px 2px rgba(255, 255, 255, 0.45);
  transform: translate(-3px, -3px);
  box-sizing: content-box;
  pointer-events: none;
`;

const HoleButton = styled.button`
  position: absolute;
  background: transparent;
  cursor: pointer;
  pointer-events: auto;

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 4px;
  }
`;

const Hint = styled.p`
  position: absolute;
  padding: 0.375rem 0.75rem; /* 6px 12px */
  border-radius: 999px;
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[800]};
  background-color: ${({ theme }) => theme.neutral[0]};
  white-space: nowrap;
  transform: translateX(-50%);
`;
