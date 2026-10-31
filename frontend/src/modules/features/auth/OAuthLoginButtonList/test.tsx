import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { OAUTH_PROVIDERS } from "./constants/oauthProviders";

import OAuthLoginButtonList from ".";

// 넘어가지 못하고 화면이 남았을 때 처리 중을 푸는 데까지 기다리는 시간
const REDIRECT_TIMEOUT_MS = 10_000;

// jsdom은 다른 주소로 이동하지 못해요. 보내려는 주소를 받아 두는 가짜 location으로 바꿔, 어디로 몇 번 보냈는지 확인해요
let visitedUrls: string[];

beforeEach(() => {
  visitedUrls = [];
  vi.stubGlobal("location", {
    set href(url: string) {
      visitedUrls.push(url);
    },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const renderButtonList = () => {
  render(
    <ThemeProvider theme={theme}>
      <OAuthLoginButtonList />
    </ThemeProvider>,
  );

  // 누르기 전과 뒤에 같은 버튼의 상태를 보려고, 처음에 찾은 버튼을 화면 순서대로 돌려줘요
  return screen.getAllByRole("button");
};

// 뒤로 가기로 돌아오면 브라우저가 떠날 때의 화면을 되살리고 pageshow를 알려요
const returnByBackButton = () =>
  act(() => {
    window.dispatchEvent(new Event("pageshow"));
  });

const advanceTimers = (ms: number) =>
  act(() => {
    vi.advanceTimersByTime(ms);
  });

const expectAllEnabled = (buttons: HTMLElement[]) => {
  for (const button of buttons) {
    expect(button).toHaveAttribute("aria-busy", "false");
    expect(button).toBeEnabled();
  }
};

describe("OAuthLoginButtonList", () => {
  it("GitHub, Google, 카카오 순서로 세 버튼과 같은 계정 안내를 보여 준다", () => {
    const buttons = renderButtonList();

    expect(buttons.map((button) => button.textContent)).toEqual(
      OAUTH_PROVIDERS.map(({ label }) => label),
    );
    expect(OAUTH_PROVIDERS.map(({ provider }) => provider)).toEqual([
      "github",
      "google",
      "kakao",
    ]);
    expect(
      screen.getByText("처음 가입한 방법으로 로그인해야 같은 계정이에요"),
    ).toBeInTheDocument();
  });

  it.each(OAUTH_PROVIDERS.map(({ provider }, index) => ({ provider, index })))(
    "$provider 버튼을 누르면 그 수단의 로그인 시작 주소로 보낸다",
    ({ provider, index }) => {
      const buttons = renderButtonList();

      fireEvent.click(buttons[index]);

      expect(visitedUrls).toEqual([
        expect.stringMatching(
          new RegExp(`/oauth2/authorization/${provider}$`),
        ),
      ]);
    },
  );

  it("하나를 누르면 누른 버튼은 처리 중이 되고, 세 버튼 모두 다시 눌러도 한 번만 보낸다", () => {
    const [githubButton, googleButton, kakaoButton] = renderButtonList();

    fireEvent.click(kakaoButton);

    expect(kakaoButton).toHaveAttribute("aria-busy", "true");
    expect(githubButton).toHaveAttribute("aria-busy", "false");
    expect(googleButton).toHaveAttribute("aria-busy", "false");
    for (const button of [githubButton, googleButton, kakaoButton]) {
      expect(button).toBeDisabled();
      fireEvent.click(button);
    }

    expect(visitedUrls).toHaveLength(1);
  });

  it("뒤로 가기로 로그인 화면에 돌아오면 처리 중이 풀려 세 버튼을 다시 누를 수 있다", () => {
    const buttons = renderButtonList();

    fireEvent.click(buttons[1]);
    returnByBackButton();

    expectAllEnabled(buttons);

    fireEvent.click(buttons[2]);

    expect(visitedUrls).toHaveLength(2);
  });

  it("넘어가지 못하고 화면이 남으면 10초 뒤에 처리 중이 풀려 세 버튼을 다시 누를 수 있다", () => {
    vi.useFakeTimers();
    const buttons = renderButtonList();

    fireEvent.click(buttons[0]);
    advanceTimers(REDIRECT_TIMEOUT_MS - 1);

    expect(buttons[0]).toHaveAttribute("aria-busy", "true");
    for (const button of buttons) {
      expect(button).toBeDisabled();
    }

    advanceTimers(1);

    expectAllEnabled(buttons);

    fireEvent.click(buttons[0]);

    expect(visitedUrls).toHaveLength(2);
  });
});
