import { render, screen } from "@testing-library/react";
import { Suspense } from "react";
import type { RouteObject } from "react-router";
import { describe, expect, it, vi } from "vitest";

import lazyPage, { preloadPages } from ".";

const PAGE_TEXT = "페이지";
const FALLBACK_TEXT = "불러오는 중";

const createPage = () => {
  const load = vi.fn(() =>
    Promise.resolve({ default: () => <p>{PAGE_TEXT}</p> }),
  );

  return { Page: lazyPage(load), load };
};

describe("lazyPage", () => {
  it("코드를 받는 동안에는 Suspense 대체 화면을 보여 주고, 받으면 페이지를 그린다", async () => {
    const { Page } = createPage();

    render(
      <Suspense fallback={<p>{FALLBACK_TEXT}</p>}>
        <Page />
      </Suspense>,
    );

    expect(screen.getByText(FALLBACK_TEXT)).toBeInTheDocument();
    expect(await screen.findByText(PAGE_TEXT)).toBeInTheDocument();
  });

  it("미리 받은 페이지는 기다리지 않고 바로 그리고, 코드를 한 번만 받는다", async () => {
    const { Page, load } = createPage();

    await Page.preload();
    render(
      <Suspense fallback={<p>{FALLBACK_TEXT}</p>}>
        <Page />
      </Suspense>,
    );

    expect(screen.getByText(PAGE_TEXT)).toBeInTheDocument();
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("코드를 받지 못하면 실패를 그대로 알리고, 다음 시도에서 다시 받는다", async () => {
    const error = new Error("ChunkLoadError");
    const load = vi
      .fn()
      .mockRejectedValueOnce(error)
      .mockResolvedValueOnce({ default: () => <p>{PAGE_TEXT}</p> });
    const Page = lazyPage(load);

    await expect(Page.preload()).rejects.toBe(error);
    await Page.preload();

    expect(load).toHaveBeenCalledTimes(2);
  });
});

describe("preloadPages", () => {
  it("주소에 맞는 라우트가 감싼 페이지까지 미리 받고, 맞지 않는 라우트의 페이지는 받지 않는다", () => {
    const home = createPage();
    const landing = createPage();
    const other = createPage();
    const routes: RouteObject[] = [
      {
        path: "/",
        element: (
          <div>
            <landing.Page />
          </div>
        ),
        children: [{ index: true, element: <home.Page /> }],
      },
      { path: "/other", element: <other.Page /> },
    ];

    preloadPages(routes, { pathname: "/" });

    expect(home.load).toHaveBeenCalledTimes(1);
    expect(landing.load).toHaveBeenCalledTimes(1);
    expect(other.load).not.toHaveBeenCalled();
  });
});
