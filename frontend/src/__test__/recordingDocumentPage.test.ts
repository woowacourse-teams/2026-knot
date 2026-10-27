import { GetDocumentsResponseDto } from "@api/dto/document";
import {
  GetRecordingAudioPlaybackResponseDto,
  GetRecordingDocumentResponseDto,
  GetRecordingTranscriptResponseDto,
} from "@api/dto/recordingDocument";
import { GetWorkspacesResponseDto } from "@api/dto/workspace";
import { documentsResponse } from "@api/mock/responses/document";
import {
  recordingAudioPlaybackResponse,
  recordingDocumentResponse,
  recordingTranscriptResponse,
} from "@api/mock/responses/recordingDocument";
import { workspacesResponse } from "@api/mock/responses/workspace";
import { expect, test, type Page } from "@playwright/test";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";

/**
 * 녹음 문서 화면(`/workspace/:workspaceId/recordings/:recordingId/document`) E2E.
 *
 * 문서 탭 「녹음별」 탭에서 녹음을 열어 녹음 제목 · 녹음 음성 · 회의 전체 요약 · 이 회의에서 나온 문서 · 원문 패널을 확인해요(STT-R36 · R38 · R39 · R50 · R52).
 * 워크스페이스 · 녹음 문서 · 전사 · 음성 · 문서 조회는 dev 서버의 msw mock이 답해요(`API_MOCKING`).
 * dev 서버 mock은 E2E에서 응답을 바꿀 수 없어서 완료된 녹음만 봐요. 처리 상태(정리 중 · 실패 · 정리할 내용 없음)와 요약 상태,
 * 불러오기 실패는 통합 테스트(`RecordingDocumentViewer/test.tsx`)와 스토리북에서 봐요.
 */

// mock 워크스페이스 조회는 id와 무관하게 같은 응답이라 목록 첫 워크스페이스의 id로 들어가요
const WORKSPACE_ID = new GetWorkspacesResponseDto(workspacesResponse)
  .workspaces[0].id;
const HOME_PATH = `/workspace/${WORKSPACE_ID}`;
const RECORDINGS_TAB_PATH = `${HOME_PATH}/documents/recordings`;
const expectedRecording = new GetRecordingDocumentResponseDto(
  recordingDocumentResponse,
);
const expectedTranscript = new GetRecordingTranscriptResponseDto(
  recordingTranscriptResponse,
);
const expectedAudio = new GetRecordingAudioPlaybackResponseDto(
  recordingAudioPlaybackResponse,
);
const RECORDING_DOCUMENT_PATH = `${HOME_PATH}/recordings/${expectedRecording.recordingId}/document`;
// 이 녹음에서 나온 문서를 「녹음별」 탭처럼 만든 순서로 늘어놓은 기대값이에요
const recordingDocuments = new GetDocumentsResponseDto(documentsResponse).items
  .filter(
    ({ recordingSessionId }) =>
      recordingSessionId === expectedRecording.recordingId,
  )
  .sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id,
  );
const RELATED_HEADING = "이 회의에서 나온 문서";
const NAV_LABEL = "워크스페이스 화면 이동";

/** 녹음 문서 영역. 녹음 제목이 영역의 이름이에요 */
const getViewer = (page: Page) =>
  page.getByRole("main").getByRole("region", { name: expectedRecording.title });

const getRelatedSection = (page: Page) =>
  getViewer(page).getByRole("region", { name: RELATED_HEADING });

const getPlayer = (page: Page) =>
  getViewer(page).getByRole("group", { name: "녹음 음성" });

const getTranscriptPanel = (page: Page) =>
  page.getByRole("region", { name: "원문" });

const getTranscriptToggle = (page: Page, name: "원문 보기" | "원문 닫기") =>
  page.getByRole("banner").getByRole("button", { name });

/** 복사한 글을 담아 두도록 클립보드를 대신해요. 브라우저마다 클립보드 권한이 달라 테스트가 흔들리지 않게 해요 */
const captureClipboard = (page: Page) =>
  page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: async (text: string) => {
          (window as unknown as { copiedText?: string }).copiedText = text;
        },
      },
    });
  });

const readCopiedText = (page: Page) =>
  page.evaluate(
    () => (window as unknown as { copiedText?: string }).copiedText,
  );

test.describe("녹음 문서 열기", () => {
  test("[STT-R36, STT-R52] 「녹음별」 탭의 녹음 행을 누르면 녹음 문서가 열리고, 제목 · 녹음 음성 · 회의 전체 요약 · 이 회의에서 나온 문서가 보인다 @core", async ({
    page,
  }) => {
    await page.goto(RECORDINGS_TAB_PATH);

    await page
      .getByRole("main")
      .getByRole("region", { name: expectedRecording.title })
      .getByRole("link", { name: /녹음 보기/ })
      .click();

    await expect(page).toHaveURL(RECORDING_DOCUMENT_PATH);
    const viewer = getViewer(page);
    await expect(
      viewer.getByRole("heading", { level: 2, name: expectedRecording.title }),
    ).toBeVisible();
    await expect(
      viewer.getByText(formatDate(expectedRecording.startedAt)),
    ).toBeVisible();
    await expect(
      viewer.getByText(
        formatDurationFromSeconds(expectedRecording.recordingDurationSeconds),
      ),
    ).toBeVisible();
    await expect(getPlayer(page)).toBeVisible();
    await expect(
      viewer.getByRole("heading", { name: "한눈에 보기" }),
    ).toBeVisible();
    await expect(
      viewer.getByRole("heading", { name: "회의 핵심 결론" }),
    ).toBeVisible();
    await expect(getRelatedSection(page)).toBeVisible();
  });

  test("[STT-R36] 녹음 문서에서는 GNB의 문서 탭이 현재 화면이고, 위치 표시가 문서 › 녹음 제목이다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);

    await expect(
      page
        .getByRole("navigation", { name: NAV_LABEL })
        .getByRole("button", { name: "문서" }),
    ).toHaveAttribute("aria-current", "page");
    await expect(page.getByRole("group", { name: "지금 위치" })).toContainText(
      `문서${expectedRecording.title}`,
    );
  });
});

test.describe("이 회의에서 나온 문서", () => {
  test("[STT-R52] 그 녹음의 문서를 「녹음별」 탭과 같은 순서로 제목과 한 줄 요약의 카드로 보여 주고, 옆에 문서 수를 둔다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);

    const section = getRelatedSection(page);
    const cards = section.getByRole("link");

    await expect(cards).toHaveCount(recordingDocuments.length);
    for (const [index, { title, summary }] of recordingDocuments.entries()) {
      await expect(cards.nth(index)).toContainText(title);
      if (summary !== null) {
        await expect(cards.nth(index)).toContainText(summary);
      }
    }
    await expect(
      section.getByText(String(recordingDocuments.length), { exact: true }),
    ).toBeVisible();
  });

  test("[STT-R52] 카드를 누르면 그 문서의 문서 보기가 열린다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);

    await getRelatedSection(page).getByRole("link").first().click();

    await expect(page).toHaveURL(
      `${HOME_PATH}/documents/${recordingDocuments[0].id}`,
    );
  });
});

test.describe("회의 전체 요약 복사", () => {
  test("[STT-R38] 제목 줄의 복사를 누르면 녹음 제목과 요약을 마크다운으로 복사하고, 복사됨을 보여 준다", async ({
    page,
  }) => {
    await captureClipboard(page);
    await page.goto(RECORDING_DOCUMENT_PATH);

    await getViewer(page).getByRole("button", { name: "복사" }).click();

    await expect(
      getViewer(page).getByRole("button", { name: "복사됨" }),
    ).toBeVisible();
    expect(await readCopiedText(page)).toBe(
      `# ${expectedRecording.title}\n\n${expectedRecording.meetingSummary?.content}`,
    );
  });
});

test.describe("원문 패널", () => {
  test("[STT-R39] 녹음 문서를 열면 원문 패널이 열려 있고, 그 녹음의 전사를 보여 준다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);

    const panel = getTranscriptPanel(page);
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("listitem")).toHaveCount(
      expectedTranscript.segments.length,
    );
    await expect(panel.getByText("이 녹음의 전사 전체예요.")).toBeVisible();
    await expect(getTranscriptToggle(page, "원문 닫기")).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  test("[STT-R39] 상단 바 원문 버튼으로 원문 패널을 닫고 다시 연다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);
    await expect(getTranscriptPanel(page)).toBeVisible();

    await getTranscriptToggle(page, "원문 닫기").click();
    await expect(getTranscriptPanel(page)).toHaveCount(0);

    await getTranscriptToggle(page, "원문 보기").click();
    await expect(getTranscriptPanel(page)).toBeVisible();
  });

  test("[STT-R39] 문서 보기에서 원문을 닫아 둔 회원도 녹음 문서를 열면 원문 패널이 열려 있다", async ({
    page,
  }) => {
    await page.goto(`${HOME_PATH}/documents/${recordingDocuments[0].id}`);
    await getTranscriptToggle(page, "원문 닫기").click();
    await expect(getTranscriptPanel(page)).toHaveCount(0);

    await page.goto(RECORDING_DOCUMENT_PATH);

    await expect(getTranscriptPanel(page)).toBeVisible();
  });

  test("[STT-R39] 원문 패널의 복사를 누르면 보이는 발화를 한 줄씩 복사한다", async ({
    page,
  }) => {
    await captureClipboard(page);
    await page.goto(RECORDING_DOCUMENT_PATH);
    const panel = getTranscriptPanel(page);

    await panel.getByRole("button", { name: "복사" }).click();

    await expect(panel.getByRole("button", { name: "복사됨" })).toBeVisible();
    const copiedLines = (await readCopiedText(page))?.split("\n") ?? [];
    expect(copiedLines).toHaveLength(expectedTranscript.segments.length);
    expect(copiedLines[0]).toContain(expectedTranscript.segments[0].text);
  });
});

test.describe("녹음 다시 듣기", () => {
  test("[STT-R50] 재생을 누르면 재생되어 지금 위치가 흐르고, 일시정지로 멈춘다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);
    const player = getPlayer(page);
    await expect(
      player.getByText(
        formatRecordingTime(expectedAudio.recordingDurationMillis / 1000),
      ),
    ).toBeVisible();

    await player.getByRole("button", { name: "재생" }).click();

    await expect(
      player.getByRole("button", { name: "일시정지" }),
    ).toBeVisible();
    await expect(
      player.getByText(formatRecordingTime(1), { exact: true }),
    ).toBeVisible({ timeout: 5000 });

    await player.getByRole("button", { name: "일시정지" }).click();
    await expect(player.getByRole("button", { name: "재생" })).toBeVisible();
  });

  test("[STT-R50] 재생하는 동안 위치 막대의 점은 1초 단위로 앞서 가지 않고 지금 재생 위치에 있다", async ({
    page,
  }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);
    const player = getPlayer(page);
    const slider = player.getByRole("slider", { name: "재생 위치" });

    await player.getByRole("button", { name: "재생" }).click();
    await expect(
      player.getByText(formatRecordingTime(1), { exact: true }),
    ).toBeVisible({ timeout: 5000 });

    // 막대 값과 실제 재생 위치를 같은 순간에 여러 번 읽어요.
    // 1초 단위로 반올림되면 차이가 100ms 안에 드는 때는 정각 앞뒤 0.1초씩, 1초에 0.2초뿐이에요.
    // 0.15초 넘게 띄워 5번 읽으면 그 안에 다 들어갈 수 없어서 적어도 한 번은 100ms를 넘어요.
    // 100ms는 화면을 한 번 그리는 사이(약 16ms)의 차이는 받아 주고, 시간 이벤트 간격(약 0.25초)보다는 작은 값이에요
    const sampleCount = 5;
    const sampleIntervalMs = 150;
    const allowedGapMs = 100;

    for (let sample = 0; sample < sampleCount; sample += 1) {
      const { sliderMillis, audioMillis } = await slider.evaluate((element) => {
        const audio = document.querySelector("audio");
        if (audio === null) throw new Error("audio 요소가 없어요");
        return {
          sliderMillis: Number((element as HTMLInputElement).value),
          audioMillis: audio.currentTime * 1000,
        };
      });
      expect(Math.abs(sliderMillis - audioMillis)).toBeLessThan(allowedGapMs);
      await page.waitForTimeout(sampleIntervalMs);
    }
  });

  test("[STT-R50] 위치 막대를 옮기면 지금 위치가 바뀐다", async ({ page }) => {
    await page.goto(RECORDING_DOCUMENT_PATH);
    const slider = getPlayer(page).getByRole("slider", { name: "재생 위치" });

    await slider.focus();
    await page.keyboard.press("ArrowRight");
    await page.keyboard.press("ArrowRight");

    await expect(slider).toHaveAttribute(
      "aria-valuetext",
      `${formatRecordingTime(2)} / ${formatRecordingTime(
        expectedAudio.recordingDurationMillis / 1000,
      )}`,
    );
  });
});
