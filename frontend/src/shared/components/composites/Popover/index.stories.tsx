import styled from "@emotion/styled";
import type { Meta, StoryObj } from "@storybook/react-webpack5";

import Popover from ".";

/** 팝오버에 넣을 예시 카드. 실제로는 쓰는 쪽(확인한 사람 목록)이 카드 겉모양까지 가져요 */
const PeopleCard = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  min-width: 10rem; /* 160px */
  margin: 0;
  padding: 0.75rem 1rem; /* 12px 16px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
  color: ${({ theme }) => theme.neutral[800]};
  list-style: none;
`;

const Trigger = styled.span`
  display: inline-block;
  padding: 0.25rem 0.5rem; /* 4px 8px */
  border-radius: 0.5rem; /* 8px */
  background-color: ${({ theme }) => theme.neutral[100]};
  color: ${({ theme }) => theme.neutral[700]};
  cursor: default;
`;

/** 트리거를 오른쪽 끝, 화면 아래 끝에 둬서 정렬과 위로 뒤집힘을 함께 볼 수 있게 해요 */
const Stage = styled.div<{ isAtBottom?: boolean }>`
  display: flex;
  justify-content: flex-end;
  align-items: ${({ isAtBottom }) => (isAtBottom ? "flex-end" : "flex-start")};
  height: 20rem; /* 320px */
  padding: 1rem;
`;

/**
 * 트리거에 포인터를 올리거나 트리거를 누르면 내용을 트리거 근처에 띄우는 팝오버예요. 문서 보기의 「2명 확인했어요」에 올리면 뜨는 확인한 사람 목록이 이 팝오버를 씁니다.
 *
 * **무엇으로 여나 (`openOn`)**
 * - `hover`(기본값): 포인터를 올리면 열려요. 잠깐 보고 마는 정보에 써요. 예: 확인한 사람 목록
 * - `click`: 트리거를 누르면 열리고 다시 누르면 닫혀요. 안에서 읽을 거리가 많은 패널에 써요. 예: GNB의 새 소식 패널
 *
 * **어떻게 뜨나**
 * - 트리거 8px 아래에 `placement`에 맞춰 왼쪽 끝(`bottom-start`)이나 오른쪽 끝(`bottom-end`)을 맞춰 떠요.
 * - 아래 공간이 모자라고 위쪽이 더 넓으면 위로 뒤집혀 떠요. 긴 내용은 위아래 16px 여백 안으로 옮겨요.
 * - 열릴 때는 위쪽 끝에서 세로로 펼쳐지며(0.2초) 나타나고, 닫힐 때는 다시 접히며(0.25초) 사라져요.
 *   움직임 줄이기 설정을 켠 사용자에게는 모션 없이 바로 나타나요.
 *
 * **동작 규칙**
 * - 포인터가 트리거에서 팝오버로 건너가는 동안 잠깐 둘 다 벗어나므로, 벗어나자마자 닫지 않고 잠시 기다렸다 닫아요.
 * - 닫히며 접히는 동안 다시 열면 그대로 다시 펼쳐져요.
 * - 어느 방식이든 바깥을 누르거나 ESC를 누르면 닫혀요.
 * - `hover`는 포인터로만 열려요. 키보드로 열려면 트리거가 버튼인 `click`을 써요.
 *
 * 무엇을 어떤 모양으로 보여줄지는 쓰는 쪽이 정하므로 이 컴포넌트는 도메인을 알지 못해요.
 */
const meta = {
  title: "Shared/Popover",
  component: Popover,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1909-5316",
    },
  },
  argTypes: {
    placement: { control: "radio", options: ["bottom-start", "bottom-end"] },
    openOn: { control: "radio", options: ["hover", "click"] },
    content: { control: false },
    children: { control: false },
  },
  args: {
    placement: "bottom-end",
    openOn: "hover",
    content: (
      <PeopleCard>
        <li>흑곰</li>
        <li>루덴스</li>
      </PeopleCard>
    ),
    children: (triggerProps) => (
      <Trigger {...triggerProps}>2명 확인했어요</Trigger>
    ),
  },
  render: (args) => (
    <Stage>
      <Popover {...args} />
    </Stage>
  ),
} satisfies Meta<typeof Popover>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 트리거 아래에 펼쳐지며 떠요. 예: 문서 보기 오른쪽 위의 확인한 사람 수 */
export const Default: Story = {};

/** 아래 공간이 모자라 트리거 위로 뒤집혀 떠요. 예: 화면 아래쪽에 있는 트리거 */
export const Flipped: Story = {
  render: (args) => (
    <Stage isAtBottom>
      <Popover {...args} />
    </Stage>
  ),
};

/** 양쪽 공간이 부족한 긴 내용도 화면 안에 유지해요. */
export const TallContent: Story = {
  args: {
    openOn: "click",
    isInitiallyOpen: true,
    content: (
      <PeopleCard
        style={{
          height: 600,
          maxHeight: "calc(100dvh - 32px)",
          overflowY: "auto",
        }}
      >
        <li>참가자 이름</li>
        <li style={{ marginTop: "auto" }}>바꾸기</li>
      </PeopleCard>
    ),
  },
};
