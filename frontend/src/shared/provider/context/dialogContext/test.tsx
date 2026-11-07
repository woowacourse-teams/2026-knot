import { act, fireEvent, render, screen } from "@testing-library/react";
import { useRef } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { DialogProvider, useDialog, useHasDialog } from ".";

const FIRST_LABEL = "첫 모달";
const SECOND_LABEL = "둘째 모달";
const HAS_DIALOG_TEXT = "모달 있음";
const NO_DIALOG_TEXT = "모달 없음";

function SecondDialog() {
  return <div role="dialog" aria-modal="true" aria-label={SECOND_LABEL} />;
}

/** 모달을 열고, 연 쪽이 돌려받은 `close`로 밖에서 닫아 보는 테스트용 화면 */
function OpenerPage() {
  const { open, closeOpenedDialog } = useDialog();
  const hasDialog = useHasDialog();
  const closeFirstRef = useRef<(() => void) | null>(null);

  const handleOpenFirstClick = () => {
    closeFirstRef.current = open(({ close }) => (
      <div role="dialog" aria-modal="true" aria-label={FIRST_LABEL}>
        <button
          type="button"
          onClick={() => {
            open(() => <SecondDialog />);
            close();
          }}
        >
          다음 모달로
        </button>
      </div>
    ));
  };

  return (
    <>
      <button type="button" onClick={handleOpenFirstClick}>
        첫 모달 열기
      </button>
      <button type="button" onClick={() => open(() => <SecondDialog />)}>
        둘째 모달 열기
      </button>
      <button type="button" onClick={() => closeFirstRef.current?.()}>
        밖에서 첫 모달 닫기
      </button>
      <button type="button" onClick={closeOpenedDialog}>
        떠 있는 모달 닫기
      </button>
      <p>{hasDialog ? HAS_DIALOG_TEXT : NO_DIALOG_TEXT}</p>
    </>
  );
}

const renderOpenerPage = () =>
  render(
    <DialogProvider>
      <OpenerPage />
    </DialogProvider>,
  );

/**
 * jsdom은 CSS 애니메이션을 돌리지 않아서, 닫힘 애니메이션이 하나 도는 브라우저를 흉내 내요.
 * 돌려받은 `finish`를 부르면 그 애니메이션이 끝나요.
 */
const mockRunningExitAnimation = () => {
  let finish = () => {};
  const finished = new Promise<void>((resolve) => {
    finish = resolve;
  });
  const animation = {
    finished,
    effect: { getComputedTiming: () => ({ endTime: 200 }) },
  } as unknown as Animation;

  Element.prototype.getAnimations = vi.fn(() => [animation]);

  return { finish };
};

describe("DialogProvider", () => {
  afterEach(() => {
    // @ts-expect-error jsdom에 없던 함수를 흉내 낸 뒤 원래대로 지워요
    delete Element.prototype.getAnimations;
  });

  it("모달을 연 쪽이 돌려받은 close로 그 모달을 닫는다", () => {
    renderOpenerPage();

    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    expect(screen.getByRole("dialog", { name: FIRST_LABEL })).toBeVisible();

    fireEvent.click(
      screen.getByRole("button", { name: "밖에서 첫 모달 닫기" }),
    );

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("다른 모달로 바뀐 뒤에는 이전 모달의 close가 지금 모달을 닫지 않는다", () => {
    renderOpenerPage();

    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    fireEvent.click(screen.getByRole("button", { name: "둘째 모달 열기" }));
    fireEvent.click(
      screen.getByRole("button", { name: "밖에서 첫 모달 닫기" }),
    );

    expect(screen.getByRole("dialog", { name: SECOND_LABEL })).toBeVisible();
  });

  it("모달 안에서 다음 모달을 먼저 열고 close를 불러도 다음 모달이 남는다", () => {
    renderOpenerPage();

    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    fireEvent.click(screen.getByRole("button", { name: "다음 모달로" }));

    expect(screen.getByRole("dialog", { name: SECOND_LABEL })).toBeVisible();
    expect(
      screen.queryByRole("dialog", { name: FIRST_LABEL }),
    ).not.toBeInTheDocument();
  });

  it("닫힘 애니메이션이 끝날 때까지 모달을 남겨 두고, 끝나면 지운다", async () => {
    renderOpenerPage();
    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    const { finish } = mockRunningExitAnimation();

    fireEvent.click(
      screen.getByRole("button", { name: "밖에서 첫 모달 닫기" }),
    );

    expect(screen.getByRole("dialog", { name: FIRST_LABEL })).toBeVisible();

    await act(async () => finish());

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("모달을 띄운 쪽이 아니어도 떠 있는 모달을 닫는다", () => {
    renderOpenerPage();
    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));

    fireEvent.click(screen.getByRole("button", { name: "떠 있는 모달 닫기" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("모달이 떠 있는 동안만 떠 있다고 알리고, 닫히는 애니메이션 중에는 치지 않는다", () => {
    renderOpenerPage();
    expect(screen.getByText(NO_DIALOG_TEXT)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    expect(screen.getByText(HAS_DIALOG_TEXT)).toBeInTheDocument();

    mockRunningExitAnimation();
    fireEvent.click(
      screen.getByRole("button", { name: "밖에서 첫 모달 닫기" }),
    );

    expect(screen.getByRole("dialog", { name: FIRST_LABEL })).toBeVisible();
    expect(screen.getByText(NO_DIALOG_TEXT)).toBeInTheDocument();
  });

  it("닫히는 중에 다른 모달을 열면 기다리지 않고 바로 바꾼다", () => {
    renderOpenerPage();
    fireEvent.click(screen.getByRole("button", { name: "첫 모달 열기" }));
    mockRunningExitAnimation();

    fireEvent.click(
      screen.getByRole("button", { name: "밖에서 첫 모달 닫기" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "둘째 모달 열기" }));

    expect(screen.getByRole("dialog", { name: SECOND_LABEL })).toBeVisible();
    expect(
      screen.queryByRole("dialog", { name: FIRST_LABEL }),
    ).not.toBeInTheDocument();
  });
});
