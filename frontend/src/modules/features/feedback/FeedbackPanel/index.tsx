import { MEDIA_QUERY } from "@constants/breakpoint";
import {
  FEEDBACK_TYPE_OPTIONS,
  MAX_FEEDBACK_CONTENT_LENGTH,
} from "@constants/feedback";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import Textarea from "@primitives/ui/Textarea";
import { useId } from "react";

import CloseIcon from "@/assets/icons/close.svg";

import useFeedbackForm from "./model/useFeedbackForm";

interface FeedbackPanelProps {
  /** 패널을 닫아요. X를 누르거나 보내기에 성공하면 불러요. 보내는 동안에는 X가 꺼져 있어요 */
  onClose: () => void;
}

/**
 * 팀에게 피드백을 보내는 패널. 유형 하나를 고르고 내용은 적고 싶을 때만 적어요(FEEDBACK-R5).
 *
 * 고른 유형과 적은 내용은 이 탭에 남아, 닫았다 다시 열어도 그대로 보여요(FEEDBACK-R6).
 * 보내는 동안에는 「보내기」가 로딩으로 바뀌고 고친 내용을 받지 않아요. 보내지 못하면 안내 문구를 보여 주고 버튼을 「다시 보내기」로 바꿔요(FEEDBACK-R8).
 * 바깥 클릭 · ESC로 닫는 일은 패널을 띄운 쪽(GNB 팝오버, 휴대폰 시트)이 맡아요.
 *
 * 열리면 유형 칩으로 포커스를 옮겨, 키보드로 연 사람이 바로 고를 수 있어요.
 * 휴대폰에서는 화면 아래에 붙는 시트 모양이에요(FEEDBACK-R2).
 */
export default function FeedbackPanel({ onClose }: FeedbackPanelProps) {
  const titleId = useId();
  const {
    type,
    content,
    isSending,
    isFailed,
    changeType,
    changeContent,
    send,
  } = useFeedbackForm({ onSent: onClose });
  const focusedType = type ?? FEEDBACK_TYPE_OPTIONS[0].type;

  return (
    <Container role="dialog" aria-labelledby={titleId}>
      <Header>
        <Title id={titleId}>피드백 보내기</Title>
        <CloseButton
          type="button"
          aria-label="닫기"
          disabled={isSending}
          onClick={onClose}
        >
          <CloseIcon size={18} />
        </CloseButton>
      </Header>

      <Fields disabled={isSending}>
        <TypeGroup>
          <Question>어떤 의견인가요?</Question>
          <TypeList>
            {FEEDBACK_TYPE_OPTIONS.map((option) => (
              <TypeChip key={option.type}>
                <input
                  type="radio"
                  name="feedbackType"
                  value={option.type}
                  checked={type === option.type}
                  onChange={() => changeType(option.type)}
                  // 패널을 열면 고른 유형(없으면 첫 유형)으로 바로 포커스를 옮겨요
                  autoFocus={option.type === focusedType}
                />
                {option.label}
              </TypeChip>
            ))}
          </TypeList>
        </TypeGroup>

        <ContentField
          aria-label="내용"
          placeholder="(선택) 어떤 화면에서 무엇을 하다가 겪었는지 적어 주시면 더 빨리 고칠 수 있어요."
          maxLength={MAX_FEEDBACK_CONTENT_LENGTH}
          value={content}
          onChange={(e) => changeContent(e.target.value)}
        />
      </Fields>

      <Notice>
        <p>
          지금 보고 있는 화면 주소와 최근 사용 기록이 함께 전달돼요. 회의
          내용이나 개인 정보는 적지 말아 주세요.
        </p>
        <p>답장은 바로 오지 않아요. 팀이 평일에 모아서 확인해요.</p>
      </Notice>

      {isFailed && (
        <ErrorMessage role="alert">
          보내지 못했어요. 적은 내용은 그대로 있으니 다시 보내 주세요.
        </ErrorMessage>
      )}

      <Actions>
        <Button
          type="button"
          size="sm"
          disabled={type === null}
          isLoading={isSending}
          onClick={send}
        >
          {isFailed ? "다시 보내기" : "보내기"}
        </Button>
      </Actions>
    </Container>
  );
}

/** 피그마 Feedback/Panel: 폭 380px · 안쪽 24px · 항목 사이 16px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  width: 23.75rem; /* 380px */
  /* 좁은 화면에서도 팝오버가 지키는 좌우 16px 여백 안에 들어가요 */
  max-width: calc(100vw - 2rem);
  padding: 1.5rem; /* 24px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: 0 8px 24px 0 rgba(31, 30, 28, 0.14);

  /* 휴대폰에서는 사이드바의 「피드백」이 화면 아래 시트로 열어요(FEEDBACK-R2). 높이는 내용만큼, 최대 화면의 85% */
  ${MEDIA_QUERY.mobile} {
    width: 100%;
    max-width: none;
    max-height: 85dvh;
    padding-bottom: calc(1.5rem + env(safe-area-inset-bottom)); /* 24px */
    border-radius: 1.25rem 1.25rem 0 0; /* 20px */
    overflow-y: auto;
  }
`;

const Header = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
`;

const Title = styled.h2`
  margin: 0;
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.heading04};
`;

/** 피그마 Btn/닫기 (X): 24px 영역 · 아이콘 18px(FEEDBACK-R4) */
const CloseButton = styled.button`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem; /* 24px */
  height: 1.5rem; /* 24px */
  border-radius: 0.375rem; /* 6px */
  color: ${({ theme }) => theme.neutral[600]};

  &:disabled {
    color: ${({ theme }) => theme.neutral[300]};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }

  /* 손가락으로 누를 때는 보이는 크기는 그대로 두고 누르는 영역만 44px로 넓혀요(RESP-R9) */
  @media (pointer: coarse) {
    position: relative;

    &::after {
      position: absolute;
      inset: -0.625rem; /* 10px */
      content: "";
    }
  }
`;

/** 보내는 동안 유형과 내용을 함께 막으려고 묶어요. 기본 테두리와 여백은 지워요 */
const Fields = styled.fieldset`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  min-width: 0;
  margin: 0;
  padding: 0;
  border: none;
`;

const TypeGroup = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
`;

const Question = styled.p`
  margin: 0;
  color: ${({ theme }) => theme.neutral[700]};
  ${({ theme }) => theme.text.caption02};
`;

const TypeList = styled.div`
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem; /* 8px */
`;

/**
 * 피그마 Chip/Select. 라디오 단추를 감춘 라벨이라 방향키로 유형을 옮겨 고를 수 있어요.
 * 고르면 먹색으로 채워져요
 */
const TypeChip = styled.label`
  padding: 0.375rem 0.75rem; /* 6px 12px */
  border: 1px solid ${({ theme }) => theme.neutral[300]};
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[700]};
  cursor: pointer;
  ${({ theme }) => theme.text.caption02};
  transition:
    background-color 0.2s ease-in,
    color 0.2s ease-in;

  & > input {
    position: absolute;
    opacity: 0;
    pointer-events: none;
  }

  &:has(input:checked) {
    border-color: ${({ theme }) => theme.neutral[800]};
    background-color: ${({ theme }) => theme.neutral[800]};
    color: ${({ theme }) => theme.neutral[0]};
  }

  &:has(input:focus-visible) {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }

  &:has(input:disabled) {
    cursor: default;
  }
`;

/** 피그마 Textarea: 높이 120px · 안쪽 12px 14px. 적는 동안 테두리가 짙어져요 */
const ContentField = styled(Textarea)`
  height: 7.5rem; /* 120px */
  padding: 0.75rem 0.875rem; /* 12px 14px */
  border: 1px solid ${({ theme }) => theme.neutral[400]};
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.body01};

  &::placeholder {
    color: ${({ theme }) => theme.neutral[500]};
  }

  &:focus {
    border-color: ${({ theme }) => theme.neutral[800]};
  }
`;

const Notice = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  padding: 0.625rem 0.75rem; /* 10px 12px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[50]};
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption01};

  & > p {
    margin: 0;
  }
`;

const ErrorMessage = styled.p`
  margin: 0;
  padding: 0.625rem 0.75rem; /* 10px 12px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.sub.warning[50]};
  color: ${({ theme }) => theme.sub.warning[700]};
  ${({ theme }) => theme.text.caption02};
`;

const Actions = styled.div`
  display: flex;
  justify-content: flex-end;
`;
