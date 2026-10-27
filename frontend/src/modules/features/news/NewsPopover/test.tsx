import {
  GetReleaseNotesResponseDto,
  ReleaseNoteItemDto,
} from "@api/dto/releaseNote";
import { FEEDBACK_RELAY_PATH } from "@api/fetch/relay/f";
import { RELEASE_NOTES_RELAY_PATH } from "@api/fetch/relay/n";
import {
  imageReleaseNoteItems,
  releaseNotesResponse,
} from "@api/mock/responses/releaseNote";
import { mockServer } from "@api/mock/server";
import useSendFeedbackMutation from "@api/mutations/useSendFeedbackMutation";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { useRecordingStore } from "@store/recordingStore";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ReleaseNote } from "@/shared/types/releaseNote";

import NewsPopover from ".";

import { LAST_SEEN_NOTE_ID_KEY } from "./constants/lastSeenNote";
import { formatReleaseDate } from "./utils/formatReleaseDate";

/** 안 읽은 소식이 있으면 뒤에 붙는 안내까지 받아요 */
const BUTTON_NAME = /^새 소식과 피드백/;
const READ_BUTTON_NAME = "새 소식과 피드백";
const UNREAD_BUTTON_NAME = "새 소식과 피드백, 읽지 않은 소식 있음";
/** 패널 머리의 설명. 패널이 열려 있는지 이 문구로 봐요 */
const PANEL_DESCRIPTION = "새로 생기거나 달라진 점을 모아 둬요";
const WORKSPACE_ID = "1";

const FEEDBACK_PANEL_NAME = "피드백 패널";

const NOTES = new GetReleaseNotesResponseDto(releaseNotesResponse).notes;
const ALL_ITEMS = NOTES.flatMap(({ items }) => items);
/** 사진 칸 경우별 항목의 기대값. Worker 응답 모양의 mock을 응답 DTO로 바꾼 값이에요 */
const toExpectedImageItem = (key: keyof typeof imageReleaseNoteItems) =>
  new ReleaseNoteItemDto(imageReleaseNoteItems[key]);

/** 레이아웃이 넘기는 피드백 패널 자리. 닫기와 보내기만 해 봐요 */
function FeedbackPanelStub({ onClose }: { onClose: () => void }) {
  const { mutate, isPending } = useSendFeedbackMutation();

  return (
    <section aria-label={FEEDBACK_PANEL_NAME}>
      {isPending && <p>보내는 중</p>}
      <button type="button" onClick={onClose}>
        피드백 패널 닫기
      </button>
      <button
        type="button"
        onClick={() =>
          mutate({ type: "bug", content: "", pageUrl: window.location.href })
        }
      >
        피드백 보내기 요청
      </button>
    </section>
  );
}

/** 노트를 받은 뒤 버튼이 그려질 때까지 기다려요. `notes`를 넘기면 Worker가 그 노트를 돌려줘요 */
const renderPopover = async (notes?: ReleaseNote[]) => {
  if (notes) {
    mockServer.use(
      http.get(`*${RELEASE_NOTES_RELAY_PATH}`, () => HttpResponse.json(notes)),
    );
  }
  // 받지 못하는 경우를 바로 보려고 다시 시도하지 않아요
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        path: PATH_ROUTE.WORKSPACE_HOME,
        element: (
          <NewsPopover
            renderFeedbackPanel={({ onClose }) => (
              <FeedbackPanelStub onClose={onClose} />
            )}
          />
        ),
      },
      { path: PATH_ROUTE.CHAT, element: null },
    ],
    {
      initialEntries: [
        getRouterPath({
          routeKey: "WORKSPACE_HOME",
          params: { workspaceId: WORKSPACE_ID },
        }),
      ],
    },
  );

  const rendered = render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return {
    ...rendered,
    router,
    button: await screen.findByRole("button", { name: BUTTON_NAME }),
  };
};

/** 버튼을 눌러 메뉴를 연 상태로 그려요 */
const renderOpenMenu = async (notes?: ReleaseNote[]) => {
  const rendered = await renderPopover(notes);
  fireEvent.click(rendered.button);

  return rendered;
};

/** 메뉴의 「새 소식」 항목. 안 읽은 소식이 있으면 이름 뒤에 NEW가 붙어요 */
const getNewsMenuItem = () =>
  within(screen.getByRole("navigation")).getByRole("button", {
    name: /^새 소식/,
  });

/** 메뉴에서 「새 소식」을 골라 새 소식 패널을 연 상태로 그려요 */
const renderOpenPanel = async (notes?: ReleaseNote[]) => {
  const rendered = await renderOpenMenu(notes);
  fireEvent.click(getNewsMenuItem());

  return rendered;
};

/** 메뉴에서 「피드백 보내기」를 골라 피드백 패널을 연 상태로 그려요 */
const renderOpenFeedbackPanel = async () => {
  const rendered = await renderOpenMenu();
  fireEvent.click(screen.getByRole("button", { name: "피드백 보내기" }));

  return rendered;
};

describe("NewsPopover", () => {
  // 새 소식을 이미 열어 본 사용자로 두고, 처음 확인하는 사용자는 그 describe에서 지워요
  beforeEach(() => localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, "v0.0.1"));
  afterEach(() => {
    localStorage.clear();
    useRecordingStore.setState(useRecordingStore.getInitialState());
    vi.unstubAllGlobals();
  });

  describe("처음 확인하는 사용자", () => {
    beforeEach(() => localStorage.removeItem(LAST_SEEN_NOTE_ID_KEY));

    it("새 소식을 한 번도 열지 않았으면 노트를 받은 뒤 메뉴 없이 새 소식 패널을 저절로 연다", async () => {
      const rendering = renderPopover();

      // 노트를 받기 전에는 열지 정할 수 없어 버튼을 그리지 않아요
      expect(
        screen.queryByRole("button", { name: BUTTON_NAME }),
      ).not.toBeInTheDocument();

      const { button } = await rendering;

      expect(button).toHaveAttribute("aria-expanded", "true");
      expect(screen.getByText(PANEL_DESCRIPTION)).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "피드백 보내기" }),
      ).not.toBeInTheDocument();
    });

    it("저절로 연 패널을 닫고 버튼으로 다시 열면 메뉴부터 보여 준다", async () => {
      const { button } = await renderPopover();

      fireEvent.click(button);
      fireEvent.click(button);

      expect(
        screen.getByRole("button", { name: "새 소식" }),
      ).toBeInTheDocument();
      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
    });

    it("저절로 열면 맨 위 노트를 읽음으로 남겨 다음에는 저절로 열지 않는다", async () => {
      const { unmount } = await renderPopover();

      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBe(NOTES[0].id);

      unmount();
      const { button } = await renderPopover();

      expect(button).toHaveAttribute("aria-expanded", "false");
    });

    it("노트가 없으면 저절로 열지 않는다", async () => {
      const { button } = await renderPopover([]);

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBeNull();
    });

    it("노트를 받지 못하면 저절로 열지 않고, 새 소식 패널은 빈 상태를 보여 준다", async () => {
      mockServer.use(
        http.get(
          `*${RELEASE_NOTES_RELAY_PATH}`,
          () => new HttpResponse(null, { status: 503 }),
        ),
      );
      const { button } = await renderPopover();

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(button).toHaveAccessibleName(READ_BUTTON_NAME);
      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBeNull();

      fireEvent.click(button);
      fireEvent.click(screen.getByRole("button", { name: "새 소식" }));

      expect(screen.getByText("아직 새 소식이 없어요")).toBeInTheDocument();
    });

    it("녹음 중이면 저절로 열지 않고 읽음도 남기지 않아, 버튼에 안 읽은 소식을 알린다", async () => {
      useRecordingStore.setState({ status: "recording" });

      const { button } = await renderPopover();

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(button).toHaveAccessibleName(UNREAD_BUTTON_NAME);
      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBeNull();
    });

    it("저절로 연 패널은 모든 노트에 NEW를 붙이고, 버튼의 안 읽음 표시는 끈다", async () => {
      const { button } = await renderPopover();

      expect(screen.getAllByText("NEW")).toHaveLength(ALL_ITEMS.length);
      expect(button).toHaveAccessibleName(READ_BUTTON_NAME);
    });

    it("localStorage를 쓸 수 없으면 저절로 열지 않는다", async () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("SecurityError");
      });

      const { button } = await renderPopover();

      expect(button).toHaveAttribute("aria-expanded", "false");
    });
  });

  describe("안 읽은 새 소식", () => {
    // 맨 위 노트 하나만 안 읽은 사용자로 둬요
    beforeEach(() => localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, NOTES[1].id));

    it("안 읽은 노트가 있으면 버튼에 알리고 메뉴의 「새 소식」에 NEW 칩을 붙인다", async () => {
      const { button } = await renderOpenMenu();

      expect(button).toHaveAccessibleName(UNREAD_BUTTON_NAME);
      expect(
        within(screen.getByRole("navigation")).getByText("NEW"),
      ).toBeInTheDocument();
    });

    it("새 소식 패널을 열면 안 읽은 노트의 항목에만 NEW를 붙이고 버튼의 안 읽음 표시를 끈다", async () => {
      const { button } = await renderOpenPanel();

      expect(screen.getAllByText("NEW")).toHaveLength(NOTES[0].items.length);
      expect(button).toHaveAccessibleName(READ_BUTTON_NAME);
    });

    it("새 소식 패널을 닫고 다시 열면 메뉴와 패널에 NEW가 없다", async () => {
      const { button } = await renderOpenPanel();

      fireEvent.click(button);
      fireEvent.click(button);

      expect(screen.queryByText("NEW")).not.toBeInTheDocument();

      fireEvent.click(getNewsMenuItem());

      expect(screen.getByText(PANEL_DESCRIPTION)).toBeInTheDocument();
      expect(screen.queryByText("NEW")).not.toBeInTheDocument();
    });

    it("메뉴만 열고 닫으면 안 읽음 표시를 그대로 둔다", async () => {
      const { button } = await renderOpenMenu();

      fireEvent.keyDown(document, { key: "Escape" });

      expect(button).toHaveAccessibleName(UNREAD_BUTTON_NAME);
      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBe(NOTES[1].id);
    });

    it("마지막으로 연 노트가 목록에 없으면 모두 읽은 것으로 본다", async () => {
      localStorage.setItem(LAST_SEEN_NOTE_ID_KEY, "v0.0.1");

      const { button } = await renderOpenMenu();

      expect(button).toHaveAccessibleName(READ_BUTTON_NAME);
      expect(screen.queryByText("NEW")).not.toBeInTheDocument();
    });

    it("localStorage를 쓸 수 없으면 안 읽은 소식을 알리지 않는다", async () => {
      vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
        throw new Error("SecurityError");
      });

      const { button } = await renderOpenPanel();

      expect(button).toHaveAccessibleName(READ_BUTTON_NAME);
      expect(screen.queryByText("NEW")).not.toBeInTheDocument();
    });
  });

  describe("읽음", () => {
    it("메뉴에서 「새 소식」을 고르면 맨 위 노트를 읽음으로 남긴다", async () => {
      await renderOpenPanel();

      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBe(NOTES[0].id);
    });

    it("메뉴만 열거나 피드백 패널을 열면 읽음을 바꾸지 않는다", async () => {
      await renderOpenFeedbackPanel();

      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBe("v0.0.1");
    });
  });

  describe("메뉴", () => {
    it("버튼을 누르면 「새 소식」과 「피드백 보내기」 메뉴를 열고 첫 항목으로 포커스를 옮긴다", async () => {
      const { button } = await renderOpenMenu();

      expect(button).toHaveAttribute("aria-expanded", "true");
      expect(screen.getByRole("button", { name: "새 소식" })).toHaveFocus();
      expect(
        screen.getByRole("button", { name: "피드백 보내기" }),
      ).toBeInTheDocument();
      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
    });

    it("「새 소식」을 고르면 메뉴를 닫고 같은 자리에 새 소식 패널을 연다", async () => {
      const { button } = await renderOpenPanel();

      expect(button).toHaveAttribute("aria-expanded", "true");
      expect(screen.getByText(PANEL_DESCRIPTION)).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "피드백 보내기" }),
      ).not.toBeInTheDocument();
    });

    it("「피드백 보내기」를 고르면 메뉴를 닫고 같은 자리에 피드백 패널을 연다", async () => {
      await renderOpenFeedbackPanel();

      expect(
        screen.getByRole("region", { name: FEEDBACK_PANEL_NAME }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "새 소식" }),
      ).not.toBeInTheDocument();
    });

    it("휴대폰에서는 피드백을 사이드바에서 열어, 버튼을 누르면 메뉴 없이 새 소식 패널을 연다", async () => {
      vi.stubGlobal(
        "matchMedia",
        vi.fn(() => ({
          matches: true,
          addEventListener: vi.fn(),
          removeEventListener: vi.fn(),
        })),
      );

      await renderOpenMenu();

      expect(screen.getByText(PANEL_DESCRIPTION)).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "피드백 보내기" }),
      ).not.toBeInTheDocument();
      expect(localStorage.getItem(LAST_SEEN_NOTE_ID_KEY)).toBe(NOTES[0].id);
    });
  });

  describe("피드백 패널", () => {
    it("패널이 닫기를 부르면 팝오버를 닫고 버튼으로 포커스를 돌려준다", async () => {
      const { button } = await renderOpenFeedbackPanel();

      fireEvent.click(screen.getByRole("button", { name: "피드백 패널 닫기" }));

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(button).toHaveFocus();
    });

    it("보내는 동안에는 바깥 · ESC · 버튼으로 닫지 않고, 보낸 뒤에는 다시 닫을 수 있다", async () => {
      let finishSending = () => {};
      mockServer.use(
        http.post(`*${FEEDBACK_RELAY_PATH}`, async () => {
          await new Promise<void>((resolve) => {
            finishSending = resolve;
          });
          return new HttpResponse(null, { status: 201 });
        }),
      );
      const { button } = await renderOpenFeedbackPanel();

      fireEvent.click(
        screen.getByRole("button", { name: "피드백 보내기 요청" }),
      );
      await screen.findByText("보내는 중");

      fireEvent.pointerDown(document.body);
      fireEvent.keyDown(document, { key: "Escape" });
      fireEvent.click(button);

      expect(
        screen.getByRole("region", { name: FEEDBACK_PANEL_NAME }),
      ).toBeInTheDocument();

      finishSending();
      await waitFor(() =>
        expect(screen.queryByText("보내는 중")).not.toBeInTheDocument(),
      );
      fireEvent.keyDown(document, { key: "Escape" });

      expect(
        screen.queryByRole("region", { name: FEEDBACK_PANEL_NAME }),
      ).not.toBeInTheDocument();
    });
  });

  describe("여닫기", () => {
    it("처음에는 메뉴와 패널을 닫아 둔다", async () => {
      const { button } = await renderPopover();

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
    });

    it("열린 패널은 버튼을 다시 누르면 닫는다", async () => {
      const { button } = await renderOpenPanel();

      fireEvent.click(button);

      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
    });

    it("열린 패널은 바깥을 누르면 닫는다", async () => {
      await renderOpenPanel();

      fireEvent.pointerDown(document.body);

      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
    });

    it("패널 안을 눌러도 닫지 않는다", async () => {
      await renderOpenPanel();

      fireEvent.pointerDown(screen.getByText(PANEL_DESCRIPTION));

      expect(screen.getByText(PANEL_DESCRIPTION)).toBeInTheDocument();
    });

    it("열린 패널은 ESC를 누르면 닫고 버튼으로 포커스를 돌려준다", async () => {
      const { button } = await renderOpenPanel();

      fireEvent.keyDown(document, { key: "Escape" });

      expect(screen.queryByText(PANEL_DESCRIPTION)).not.toBeInTheDocument();
      expect(button).toHaveFocus();
    });
  });

  describe("노트 목록", () => {
    it("노트를 배포 날짜별로 최신부터 묶어 보여 준다", async () => {
      await renderOpenPanel();

      const dateHeadings = screen.getAllByRole("heading", { level: 3 });

      expect(dateHeadings.map(({ textContent }) => textContent)).toEqual(
        NOTES.map(({ releasedAt }) => formatReleaseDate(releasedAt)),
      );
    });

    it("같은 날 배포한 노트는 한 날짜 묶음에 모아 보여 준다", async () => {
      const sameDayNotes: ReleaseNote[] = [
        {
          id: "v1.0.1",
          releasedAt: "2026-11-02",
          items: [{ title: "나중 릴리즈 항목", description: "설명" }],
        },
        {
          id: "v1.0.0",
          releasedAt: "2026-11-02",
          items: [{ title: "먼저 릴리즈 항목", description: "설명" }],
        },
      ];
      await renderOpenPanel(sameDayNotes);

      const [dateGroup, ...rest] = screen.getAllByRole("heading", {
        level: 3,
      });

      expect(rest).toHaveLength(0);
      expect(dateGroup).toHaveTextContent("11월 2일");
      expect(
        screen.getAllByRole("listitem").map(({ textContent }) => textContent),
      ).toEqual(["나중 릴리즈 항목설명", "먼저 릴리즈 항목설명"]);
    });

    it("항목마다 제목과 설명을 보여 준다", async () => {
      await renderOpenPanel();

      ALL_ITEMS.forEach(({ title, description }) => {
        expect(screen.getByText(title)).toBeInTheDocument();
        expect(screen.getByText(description)).toBeInTheDocument();
      });
    });

    it("피드백으로 고친 항목에만 「피드백 덕분에 바뀌었어요」 칩을 붙인다", async () => {
      await renderOpenPanel();

      expect(screen.queryAllByText("피드백 덕분에 바뀌었어요")).toHaveLength(
        ALL_ITEMS.filter(({ isFromFeedback }) => isFromFeedback).length,
      );
    });

    it("사진과 「바로 가보기」는 있는 항목에만 보여 준다", async () => {
      await renderOpenPanel([
        {
          id: "v1.0.0",
          releasedAt: "2026-11-02",
          items: [
            {
              title: "사진과 바로 가보기가 있는 항목",
              description: "설명",
              image: imageReleaseNoteItems.normal.image,
              link: "CHAT",
            },
            { title: "글만 있는 항목", description: "설명" },
          ],
        },
      ]);

      expect(document.querySelectorAll("img")).toHaveLength(1);
      expect(screen.getAllByRole("link", { name: "바로 가보기" })).toHaveLength(
        1,
      );
    });

    it("GitHub 첨부 사진 주소의 사진만 그리고, 다른 주소면 사진 칸 없이 글만 보여 준다", async () => {
      await renderOpenPanel([
        {
          id: "pr-1051",
          releasedAt: "2026-11-02",
          items: [
            imageReleaseNoteItems.normal,
            imageReleaseNoteItems.disallowed,
          ],
        },
      ]);

      const images = document.querySelectorAll("img");
      expect(images).toHaveLength(1);
      expect(images[0]).toHaveAttribute(
        "src",
        toExpectedImageItem("normal").image,
      );
      expect(
        screen.getByText(toExpectedImageItem("disallowed").title),
      ).toBeVisible();
    });

    it("사진은 다 불러온 뒤에 사진 칸을 보여 준다", async () => {
      await renderOpenPanel([
        {
          id: "pr-1051",
          releasedAt: "2026-11-02",
          items: [imageReleaseNoteItems.normal],
        },
      ]);
      const image = document.querySelector("img")!;

      expect(image).not.toBeVisible();

      fireEvent.load(image);

      expect(image).toBeVisible();
    });

    it("사진을 불러오지 못하면 사진 칸만 숨기고 칩, 제목, 설명, 「바로 가보기」는 그대로 둔다", async () => {
      const broken = toExpectedImageItem("broken");
      await renderOpenPanel([
        {
          id: "pr-1051",
          releasedAt: "2026-11-02",
          items: [
            {
              ...imageReleaseNoteItems.broken,
              link: "CHAT",
              isFromFeedback: true,
            },
          ],
        },
      ]);

      fireEvent.error(document.querySelector("img")!);

      expect(document.querySelector("img")).not.toBeVisible();
      expect(screen.getByText("피드백 덕분에 바뀌었어요")).toBeVisible();
      expect(screen.getByText(broken.title)).toBeVisible();
      expect(screen.getByText(broken.description)).toBeVisible();
      expect(screen.getByRole("link", { name: "바로 가보기" })).toBeVisible();
    });

    it("앱에 없는 화면이나 워크스페이스 ID만으로 갈 수 없는 화면이면 「바로 가보기」를 숨긴다", async () => {
      await renderOpenPanel([
        {
          id: "v1.0.0",
          releasedAt: "2026-11-02",
          items: [
            { title: "갈 수 있는 화면", description: "설명", link: "CHAT" },
            { title: "지운 화면", description: "설명", link: "SETTINGS" },
            {
              title: "대화 ID가 필요한 화면",
              description: "설명",
              link: "CHAT_SESSION",
            },
            {
              title: "워크스페이스 밖 화면",
              description: "설명",
              link: "LOGIN",
            },
          ],
        },
      ]);

      expect(screen.getAllByRole("listitem")).toHaveLength(4);
      expect(screen.getAllByRole("link", { name: "바로 가보기" })).toHaveLength(
        1,
      );
    });

    it("「바로 가보기」를 누르면 지금 워크스페이스의 그 화면으로 옮긴다", async () => {
      const { router } = await renderOpenPanel([
        {
          id: "v1.0.0",
          releasedAt: "2026-11-02",
          items: [{ title: "탐색", description: "설명", link: "CHAT" }],
        },
      ]);

      fireEvent.click(screen.getByRole("link", { name: "바로 가보기" }));

      expect(router.state.location.pathname).toBe(
        getRouterPath({
          routeKey: "CHAT",
          params: { workspaceId: WORKSPACE_ID },
        }),
      );
    });

    it("노트가 없으면 빈 상태를 보여 준다", async () => {
      await renderOpenPanel([]);

      expect(screen.getByText("아직 새 소식이 없어요")).toBeInTheDocument();
      expect(
        screen.getByText("바뀐 점이 생기면 여기에 모아 둘게요"),
      ).toBeInTheDocument();
      expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
    });
  });
});
