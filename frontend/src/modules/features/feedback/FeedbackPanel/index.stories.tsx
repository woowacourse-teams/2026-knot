import { FEEDBACK_RELAY_PATH } from "@api/fetch/relay/f";
import styled from "@emotion/styled";
import { ToastProvider } from "@provider/context/toastContext";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { useState } from "react";
import { MemoryRouter } from "react-router";
import { fn, userEvent, within } from "storybook/test";

import FeedbackPanel from ".";

const FEEDBACK_REQUEST = `*${FEEDBACK_RELAY_PATH}`;
// 문서 페이지는 스토리를 한 화면에 함께 그리는데 msw는 화면에 하나뿐이라, 마지막 스토리의 응답이 모든 스토리에 적용돼요.
// 응답을 바꾸는 스토리만 따로 그려 각자의 응답을 받게 해요. 높이는 실패 안내까지 든 패널이 잘리지 않는 값이에요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 640 } };

/** GNB 아래에 뜨는 패널처럼 밝은 바탕 위 오른쪽에 둬요 */
const Stage = styled.div`
  display: flex;
  justify-content: flex-end;
  padding: 1rem 1.5rem; /* 16px 24px */
  background-color: ${({ theme }) => theme.neutral[50]};
`;

/** 유형을 고르고, 내용이 있으면 적어요 */
const fillFeedback = async (
  canvasElement: HTMLElement,
  { type = "오류가 있어요", content = "" } = {},
) => {
  const canvas = within(canvasElement);
  // 라디오 단추는 감춰져 눌리지 않아, 사람처럼 칩(라벨)을 눌러요
  await userEvent.click(canvas.getByText(type, { selector: "label" }));
  if (content) {
    await userEvent.type(
      canvas.getByRole("textbox", { name: "내용" }),
      content,
    );
  }
};

/** 예시 내용을 적고 「보내기」를 눌러요 */
const sendFeedback = async (canvasElement: HTMLElement) => {
  await fillFeedback(canvasElement, {
    content: "문서 복사 버튼을 눌렀는데 노션에 붙여 넣으니 표가 깨져요.",
  });
  await userEvent.click(
    within(canvasElement).getByRole("button", { name: "보내기" }),
  );
};

/**
 * 서비스를 쓰다 불편하거나 오류를 만난 사람이 화면을 떠나지 않고 팀에게 피드백을 보내는 패널이에요.
 * 상단 메뉴의 「소식과 피드백」 → 「피드백 보내기」로 열고, 휴대폰에서는 사이드바 맨 아래 「피드백」으로 화면 아래 시트로 열어요.
 *
 * **동작 규칙**
 * - 유형(불편해요, 오류가 있어요, 아이디어가 있어요, 좋았어요) 하나를 꼭 골라야 「보내기」가 켜져요. 내용은 적고 싶을 때만 적어요.
 * - 고른 유형과 적은 내용은 이 탭에 남아요. 패널을 닫았다 다시 열거나 새로고침해도 그대로 보이고, 보내기에 성공하면 비워요.
 * - X로 닫아요. 바깥 클릭과 ESC로 닫는 일은 패널을 띄운 쪽(상단 메뉴, 휴대폰 시트)이 맡아요.
 * - 보내는 동안에는 「보내기」가 로딩으로 바뀌고, X와 유형 · 내용이 막혀요. 바깥 클릭과 ESC로도 닫히지 않아요.
 * - 보내면 패널이 닫히고 「피드백을 보냈어요. 팀이 평일에 모아서 확인해요」 토스트가 떠요.
 * - 보내지 못하면 패널을 그대로 두고 안내 문구를 보여 주며, 버튼이 「다시 보내기」로 바뀌어요. 적은 내용은 그대로예요.
 *
 * **보낸 피드백이 가는 곳**
 * - 팀 Notion 「사용자 피드백 접수함」에 한 줄로 들어가요. 화면 주소와 그 세션의 사용 기록(PostHog 리플레이) 링크가 함께 들어가고, 닉네임은 적지 않아요.
 * - 저장된 뒤에만 PostHog에 유형과 점수(오류 1, 불편 2, 아이디어 3, 좋았어요 4)를 남겨요. 점수는 사용자에게 보이지 않아요.
 * - 스토리북에서는 실제로 보내지 않고 저장된 것처럼만 응답해요.
 */
const meta = {
  title: "Feedback/FeedbackPanel",
  component: FeedbackPanel,
  parameters: {
    design: [
      {
        name: "피드백 창구, 서비스 안 피드백 보내기",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4694-47945",
      },
      {
        name: "Feedback/Panel",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4691-46763",
      },
      {
        name: "Policy 카드",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4695-11235",
      },
    ],
  },
  args: { onClose: fn() },
  // 적던 내용은 탭에 남아 스토리끼리 이어지므로, 빈 패널로 시작해요
  beforeEach: () => {
    sessionStorage.clear();
  },
  decorators: [
    (Story) => (
      <MemoryRouter>
        <ToastProvider>
          <Stage>
            <Story />
          </Stage>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof FeedbackPanel>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 패널을 막 연 모습이에요. 유형을 고르기 전이라 「보내기」가 꺼져 있어요 */
export const Default: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4691-46666",
    },
  },
};

/** 유형만 고른 모습이에요. 내용이 비어 있어도 「보내기」가 켜져요 */
export const TypeSelected: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4827-51586",
    },
  },
  play: async ({ canvasElement }) => {
    await fillFeedback(canvasElement);
  },
};

/** 유형을 고르고 내용을 적는 중이에요. 적는 동안 입력창 테두리가 짙어져요 */
export const Writing: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4691-46689",
    },
  },
  play: async ({ canvasElement }) => {
    await fillFeedback(canvasElement, {
      content: "문서 복사 버튼을 눌렀는데 노션에 붙여 넣으니 표가 깨져요.",
    });
  },
};

/** 「보내기」를 누르고 저장되기를 기다리는 중이에요. 버튼이 로딩으로 바뀌고 X와 유형 · 내용이 막혀요 */
export const Sending: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4691-46712",
    },
    msw: {
      handlers: {
        feedback: http.post(FEEDBACK_REQUEST, async () => {
          await delay("infinite");
          return new HttpResponse(null, { status: 201 });
        }),
      },
    },
    docs: ISOLATED_DOCS,
  },
  play: async ({ canvasElement }) => {
    await sendFeedback(canvasElement);
  },
};

/** 보내지 못한 모습이에요. 예: Notion이 저장을 거절했을 때. 적은 내용은 그대로 두고 「다시 보내기」로 바뀌어요 */
export const Failed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4691-46738",
    },
    msw: {
      handlers: {
        feedback: http.post(
          FEEDBACK_REQUEST,
          () => new HttpResponse(null, { status: 502 }),
        ),
      },
    },
    docs: ISOLATED_DOCS,
  },
  play: async ({ canvasElement }) => {
    await sendFeedback(canvasElement);
  },
};

/** 보낸 뒤의 모습이에요. 패널이 닫히고 완료 토스트가 떠요 */
export const Sent: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4694-48550",
    },
  },
  // 패널을 띄운 쪽처럼 닫기를 받으면 패널을 지워요
  render: function SentStory(args) {
    const [isOpen, setIsOpen] = useState(true);

    return (
      <>
        {isOpen && (
          <FeedbackPanel
            {...args}
            onClose={() => {
              args.onClose();
              setIsOpen(false);
            }}
          />
        )}
      </>
    );
  },
  play: async ({ canvasElement }) => {
    await sendFeedback(canvasElement);
  },
};
