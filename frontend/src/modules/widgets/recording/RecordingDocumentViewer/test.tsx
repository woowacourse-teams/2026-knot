import {
  GetDocumentsResponseDto,
  type DocumentListItemDto,
} from "@api/dto/document";
import {
  GetRecordingAudioPlaybackResponseDto,
  GetRecordingDocumentResponseDto,
} from "@api/dto/recordingDocument";
import { documentsResponse } from "@api/mock/responses/document";
import {
  documentGenerationJobRetryResponse,
  RETRY_EXHAUSTED_JOB_ID,
} from "@api/mock/responses/documentGenerationJob";
import { recordingTranscriptionRetryResponse } from "@api/mock/responses/recording";
import {
  meetingSummaryRetryResponse,
  recordingAudioPlaybackResponse,
  recordingDocumentResponse,
} from "@api/mock/responses/recordingDocument";
import { mockServer } from "@api/mock/server";
import type { DocumentsResponse } from "@api/mock/types/document";
import type { RecordingDocumentResponse } from "@api/mock/types/recordingDocument";
import { TOAST_MESSAGES } from "@constants/toastMessages";
import { ThemeProvider } from "@emotion/react";
import { ToastProvider } from "@provider/context/toastContext";
import { theme } from "@provider/themeProvider";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { formatRecordingTime } from "@utils/formatRecordingTime";
import { delay, http, HttpResponse } from "msw";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useParams,
} from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import RecordingDocumentViewer from ".";

const WORKSPACE_ID = "1";
const expectedRecording = new GetRecordingDocumentResponseDto(
  recordingDocumentResponse,
);
const expectedAudio = new GetRecordingAudioPlaybackResponseDto(
  recordingAudioPlaybackResponse,
);
// 경로 파라미터 자리에 무엇이 와도 잡도록 fetch 상수 대신 패턴을 적어요
const RECORDING_REQUEST =
  "*/api/v1/workspaces/:workspaceId/recordings/:recordingId";
const RECORDING_DOCUMENT_REQUEST = `${RECORDING_REQUEST}/document`;
const RECORDING_TRANSCRIPT_REQUEST = `${RECORDING_REQUEST}/transcript`;
const AUDIO_PLAYBACK_REQUEST = `${RECORDING_REQUEST}/audio-playback`;
const MEETING_SUMMARY_RETRY_REQUEST = `${RECORDING_REQUEST}/meeting-summary/retry`;
const TRANSCRIPTION_RETRY_REQUEST = `${RECORDING_REQUEST}/transcription/retry`;
const DOCUMENTS_REQUEST = "*/api/v1/workspaces/:workspaceId/documents";
const JOB_RETRY_REQUEST =
  "*/api/v1/workspaces/:workspaceId/document-generation-jobs/:jobId/retry";

const COPIED_DURATION_MS = 3000;
const RELATED_HEADING = "이 회의에서 나온 문서";
const SUMMARY_DRAFTING = "회의 전체 요약을 만들고 있어요";
const SUMMARY_FAILED = "요약을 만들지 못했어요";
const NO_SUMMARY_NOTICE =
  "이 녹음은 회의 전체 요약 기능이 생기기 전에 끝나 요약이 없어요.";
const DOCUMENT_FAILED_TITLE = "문서를 만들지 못했어요";

/** 녹음 42에서 나온 문서. 「녹음별」 탭처럼 만든 순서(만든 시각, 같으면 id)로 늘어놓은 기대값이에요 */
const recordingDocuments = new GetDocumentsResponseDto(documentsResponse).items
  .filter(
    ({ recordingSessionId }) =>
      recordingSessionId === expectedRecording.recordingId,
  )
  .sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id,
  );

const respondWithRecordingDocument = (
  overrides: Partial<RecordingDocumentResponse>,
) =>
  mockServer.use(
    http.get(RECORDING_DOCUMENT_REQUEST, () =>
      HttpResponse.json({ ...recordingDocumentResponse, ...overrides }),
    ),
  );

/** 전사 조회가 주제 분류 전이나 실패라 전사가 없다고 답해요 */
const respondWithTranscriptNotFound = () =>
  mockServer.use(
    http.get(RECORDING_TRANSCRIPT_REQUEST, () =>
      HttpResponse.json(
        {
          code: "RECORDING_TRANSCRIPT_NOT_FOUND",
          message: "녹음 전사를 찾을 수 없습니다.",
        },
        { status: 404 },
      ),
    ),
  );

/** 주소의 문서 id를 보여 주는 문서 보기 자리. 카드를 눌러 옮겨 갔는지 확인할 때 써요 */
function DocumentPageStub() {
  const { documentId } = useParams();

  return <p>문서 보기 {documentId}</p>;
}

const renderViewer = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      {
        element: (
          <ToastProvider>
            <Outlet />
          </ToastProvider>
        ),
        children: [
          {
            path: PATH_ROUTE.RECORDING_DOCUMENT,
            element: <RecordingDocumentViewer />,
          },
          { path: PATH_ROUTE.DOCUMENT, element: <DocumentPageStub /> },
          { path: PATH_ROUTE.LOGIN, element: <p>로그인 화면</p> },
        ],
      },
    ],
    {
      initialEntries: [
        getRouterPath({
          routeKey: "RECORDING_DOCUMENT",
          params: {
            workspaceId: WORKSPACE_ID,
            recordingId: String(expectedRecording.recordingId),
          },
        }),
      ],
    },
  );

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>,
  );
};

/** 녹음 제목. 녹음 문서를 받은 뒤에 보여요 */
const findTitle = () =>
  screen.findByRole("heading", { level: 2, name: expectedRecording.title });

const getRelatedSection = () =>
  screen.getByRole("region", { name: RELATED_HEADING });

/** 「이 회의에서 나온 문서」의 문서 카드 링크. 실패 카드는 링크가 아니라 들어가지 않아요 */
const findDocumentCardLinks = async () => {
  const section = await screen.findByRole("region", { name: RELATED_HEADING });

  return within(section).findAllByRole("link");
};

const expectCardTitles = async (documents: DocumentListItemDto[]) => {
  const links = await findDocumentCardLinks();

  expect(links.map((link) => link.textContent)).toEqual(
    documents.map(({ title, summary }) => `${title}${summary ?? ""}`),
  );
};

describe("RecordingDocumentViewer", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    Reflect.deleteProperty(navigator, "clipboard");
  });

  describe("완료 (STT-R36 · R37)", () => {
    it("녹음 제목과 녹음을 시작한 날, 녹음 길이를 보여 준다", async () => {
      renderViewer();

      expect(await findTitle()).toBeInTheDocument();
      expect(
        screen.getByText(formatDate(expectedRecording.startedAt)),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          formatDurationFromSeconds(expectedRecording.recordingDurationSeconds),
        ),
      ).toBeInTheDocument();
    });

    it("녹음 음성, 회의 전체 요약, 이 회의에서 나온 문서를 함께 보여 준다", async () => {
      renderViewer();

      expect(
        await screen.findByRole("group", { name: "녹음 음성" }),
      ).toBeInTheDocument();
      expect(
        await screen.findByRole("heading", { name: "한눈에 보기" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { level: 3, name: RELATED_HEADING }),
      ).toBeInTheDocument();
    });
  });

  describe("회의 전체 요약 (STT-R38)", () => {
    it("완료된 요약의 마지막 줄 회의 핵심 결론을 제목과 문장으로 나눠 보여 준다", async () => {
      renderViewer();

      expect(
        await screen.findByRole("heading", { name: "회의 핵심 결론" }),
      ).toBeInTheDocument();
      expect(
        screen.getByText(
          "탈퇴한 사용자의 글은 댓글이 있으면 남기고, 홈 개편은 다음 회의에서 정한다.",
        ),
      ).toBeInTheDocument();
    });

    it("제목 줄의 복사를 누르면 녹음 제목과 요약을 마크다운으로 복사하고, 3초 동안 복사됨을 보여 준다", async () => {
      const writeText = vi
        .fn<(text: string) => Promise<void>>()
        .mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText },
        configurable: true,
      });
      renderViewer();
      await findTitle();
      vi.useFakeTimers();

      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "복사" }));
      });

      expect(writeText).toHaveBeenCalledWith(
        `# ${expectedRecording.title}\n\n${expectedRecording.meetingSummary?.content}`,
      );
      expect(
        screen.getByRole("button", { name: "복사됨" }),
      ).toBeInTheDocument();

      await act(async () => {
        vi.advanceTimersByTime(COPIED_DURATION_MS);
      });

      expect(screen.getByRole("button", { name: "복사" })).toBeInTheDocument();
    });

    it("요약을 만드는 중이면 만들고 있다고 알리고 복사 버튼을 두지 않는다", async () => {
      respondWithRecordingDocument({
        meetingSummary: { status: "RUNNING", content: null, retryable: false },
      });
      renderViewer();

      expect(await screen.findByText(SUMMARY_DRAFTING)).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "복사" }),
      ).not.toBeInTheDocument();
      expect(getRelatedSection()).toBeInTheDocument();
    });

    it("요약이 실패했고 다시 시도할 수 있으면 「다시 시도」로 요약만 다시 접수하고, 만드는 중으로 바꾼다", async () => {
      let summaryStatus: "FAILED" | "QUEUED" = "FAILED";
      const retryRequests: string[] = [];
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, () =>
          HttpResponse.json({
            ...recordingDocumentResponse,
            meetingSummary: {
              status: summaryStatus,
              content: null,
              retryable: summaryStatus === "FAILED",
            },
          }),
        ),
        http.post(MEETING_SUMMARY_RETRY_REQUEST, ({ params }) => {
          retryRequests.push(String(params.recordingId));
          summaryStatus = "QUEUED";
          return HttpResponse.json(meetingSummaryRetryResponse, {
            status: 202,
          });
        }),
      );
      renderViewer();

      const failedNotice = await screen.findByRole("alert");
      expect(failedNotice).toHaveTextContent(SUMMARY_FAILED);
      expect(
        screen.queryByRole("button", { name: "복사" }),
      ).not.toBeInTheDocument();

      fireEvent.click(
        within(failedNotice).getByRole("button", { name: "다시 시도" }),
      );

      expect(await screen.findByText(SUMMARY_DRAFTING)).toBeInTheDocument();
      expect(retryRequests).toEqual([String(expectedRecording.recordingId)]);
    });

    it("요약이 실패했고 다시 시도할 수 없으면 「다시 시도」 없이 실패만 알린다", async () => {
      respondWithRecordingDocument({
        meetingSummary: { status: "FAILED", content: null, retryable: false },
      });
      renderViewer();

      const failedNotice = await screen.findByRole("alert");

      expect(failedNotice).toHaveTextContent(SUMMARY_FAILED);
      expect(
        within(failedNotice).queryByRole("button", { name: "다시 시도" }),
      ).not.toBeInTheDocument();
    });

    it("요약 다시 시도가 서버 오류로 실패하면 오류 토스트로 알린다", async () => {
      respondWithRecordingDocument({
        meetingSummary: { status: "FAILED", content: null, retryable: true },
      });
      mockServer.use(
        http.post(
          MEETING_SUMMARY_RETRY_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      );
      renderViewer();

      fireEvent.click(
        within(await screen.findByRole("alert")).getByRole("button", {
          name: "다시 시도",
        }),
      );

      expect(
        await screen.findByText(TOAST_MESSAGES.RETRY_REQUEST_FAILED.message),
      ).toBeInTheDocument();
    });

    it("요약 기록이 없는 녹음은 요약이 없는 까닭만 알리고, 실패나 만드는 중으로 보이지 않는다", async () => {
      respondWithRecordingDocument({ meetingSummary: null });
      renderViewer();

      expect(await screen.findByText(NO_SUMMARY_NOTICE)).toBeInTheDocument();
      expect(screen.queryByText(SUMMARY_FAILED)).not.toBeInTheDocument();
      expect(screen.queryByText(SUMMARY_DRAFTING)).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "복사" }),
      ).not.toBeInTheDocument();
      expect(getRelatedSection()).toBeInTheDocument();
    });
  });

  describe("이 회의에서 나온 문서 (STT-R52)", () => {
    it("그 녹음의 문서를 만든 순서대로 제목과 한 줄 요약의 카드로 보여 주고, 옆에 문서 수를 둔다", async () => {
      renderViewer();

      await expectCardTitles(recordingDocuments);
      expect(
        within(getRelatedSection()).getByText(
          String(recordingDocuments.length),
        ),
      ).toBeInTheDocument();
    });

    it("카드를 누르면 그 문서의 문서 보기로 간다", async () => {
      renderViewer();

      const [firstCard] = await findDocumentCardLinks();
      fireEvent.click(firstCard);

      expect(
        await screen.findByText(`문서 보기 ${recordingDocuments[0].id}`),
      ).toBeInTheDocument();
    });

    it("목록을 불러오지 못하면 그 자리에만 안내와 「다시 시도」를 두고, 누르면 다시 받아 카드를 보여 준다", async () => {
      mockServer.use(
        http.get(
          DOCUMENTS_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
          { once: true },
        ),
      );
      renderViewer();

      const section = await screen.findByRole("region", {
        name: RELATED_HEADING,
      });
      const notice = await within(section).findByRole("alert");
      expect(notice).toHaveTextContent(
        "목록을 불러오지 못했어요. 다시 시도해 주세요.",
      );
      expect(await findTitle()).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { name: "한눈에 보기" }),
      ).toBeInTheDocument();

      fireEvent.click(
        within(notice).getByRole("button", { name: "다시 시도" }),
      );

      await expectCardTitles(recordingDocuments);
    });
  });

  describe("처리 상태 (STT-R42)", () => {
    it("문서 정리 중이면 정리 중임을 보여 주고 녹음 음성 · 요약 · 문서 목록 · 복사를 두지 않는다", async () => {
      respondWithRecordingDocument({
        processingStatus: "PROCESSING",
        meetingSummary: null,
      });
      renderViewer();

      expect(
        await screen.findByRole("heading", {
          name: "회의 내용을 바탕으로 문서를 정리하고 있어요",
        }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("group", { name: "녹음 음성" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: RELATED_HEADING }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "복사" }),
      ).not.toBeInTheDocument();
    });

    it("문서 정리 중이면 다시 물어봐, 정리가 끝나면 저절로 녹음 문서를 보여 준다", async () => {
      let requestCount = 0;
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, () => {
          requestCount += 1;
          return HttpResponse.json(
            requestCount === 1
              ? {
                  ...recordingDocumentResponse,
                  processingStatus: "PROCESSING",
                  meetingSummary: null,
                }
              : recordingDocumentResponse,
          );
        }),
      );
      renderViewer();

      expect(
        await screen.findByRole("heading", {
          name: "회의 내용을 바탕으로 문서를 정리하고 있어요",
        }),
      ).toBeInTheDocument();

      expect(
        await screen.findByRole(
          "heading",
          { name: "한눈에 보기" },
          { timeout: 5000 },
        ),
      ).toBeInTheDocument();
    });

    it("전사 실패면 「녹음을 글로 옮기지 못했어요」를 두고, 「다시 시도」로 전사를 다시 접수해 정리 중으로 바꾼다", async () => {
      let processingStatus: "TRANSCRIPTION_FAILED" | "PROCESSING" =
        "TRANSCRIPTION_FAILED";
      let retryCount = 0;
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, () =>
          HttpResponse.json({
            ...recordingDocumentResponse,
            processingStatus,
            retryable: processingStatus === "TRANSCRIPTION_FAILED",
            meetingSummary: null,
          }),
        ),
        http.post(TRANSCRIPTION_RETRY_REQUEST, () => {
          retryCount += 1;
          processingStatus = "PROCESSING";
          return HttpResponse.json(recordingTranscriptionRetryResponse, {
            status: 202,
          });
        }),
      );
      renderViewer();

      expect(
        await screen.findByRole("heading", {
          name: "녹음을 글로 옮기지 못했어요",
        }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("group", { name: "녹음 음성" }),
      ).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

      expect(
        await screen.findByRole("heading", {
          name: "회의 내용을 바탕으로 문서를 정리하고 있어요",
        }),
      ).toBeInTheDocument();
      expect(retryCount).toBe(1);
    });

    it("다시 시도할 수 없는 전사 실패에는 「다시 시도」를 두지 않는다", async () => {
      respondWithRecordingDocument({
        processingStatus: "TRANSCRIPTION_FAILED",
        retryable: false,
        meetingSummary: null,
      });
      renderViewer();

      expect(
        await screen.findByRole("heading", {
          name: "녹음을 글로 옮기지 못했어요",
        }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "다시 시도" }),
      ).not.toBeInTheDocument();
    });

    it("주제 분류 실패면 「문서를 만들지 못했어요」만 두고 녹음 음성 · 요약 · 문서 목록을 두지 않으며, 「다시 시도」로 실패한 작업을 다시 접수한다", async () => {
      const retriedJobIds: string[] = [];
      respondWithRecordingDocument({
        processingStatus: "DOCUMENT_FAILED",
        retryable: true,
        retryableJobIds: [documentGenerationJobRetryResponse.jobId],
        meetingSummary: null,
      });
      respondWithTranscriptNotFound();
      mockServer.use(
        http.post(JOB_RETRY_REQUEST, ({ params }) => {
          retriedJobIds.push(String(params.jobId));
          return HttpResponse.json(documentGenerationJobRetryResponse, {
            status: 202,
          });
        }),
      );
      renderViewer();

      expect(
        await screen.findByRole("heading", { name: DOCUMENT_FAILED_TITLE }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("group", { name: "녹음 음성" }),
      ).not.toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: RELATED_HEADING }),
      ).not.toBeInTheDocument();
      expect(screen.queryByText(NO_SUMMARY_NOTICE)).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

      await waitFor(() =>
        expect(retriedJobIds).toEqual([
          String(documentGenerationJobRetryResponse.jobId),
        ]),
      );
    });

    it("문서 생성 실패면 녹음 음성 · 요약을 그대로 두고, 만든 문서 카드 뒤에 실패 카드 하나를 둔다", async () => {
      respondWithRecordingDocument({
        processingStatus: "DOCUMENT_FAILED",
        retryable: true,
        retryableJobIds: [
          documentGenerationJobRetryResponse.jobId,
          RETRY_EXHAUSTED_JOB_ID,
        ],
      });
      renderViewer();

      await expectCardTitles(recordingDocuments);
      const section = getRelatedSection();
      const items = within(section).getAllByRole("listitem");

      expect(items).toHaveLength(recordingDocuments.length + 1);
      expect(items[items.length - 1]).toHaveTextContent(DOCUMENT_FAILED_TITLE);
      expect(items[items.length - 1]).toHaveTextContent(
        "녹음은 보관해 두었어요.",
      );
      expect(
        within(section).getByText(String(recordingDocuments.length)),
      ).toBeInTheDocument();
      expect(
        await screen.findByRole("group", { name: "녹음 음성" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("heading", { name: "한눈에 보기" }),
      ).toBeInTheDocument();
    });

    it("실패 카드의 「다시 시도」는 실패한 문서 작업을 모두 다시 접수하고 정리 중으로 바꾼다", async () => {
      let processingStatus: "DOCUMENT_FAILED" | "PROCESSING" =
        "DOCUMENT_FAILED";
      const retriedJobIds: string[] = [];
      const jobIds = [documentGenerationJobRetryResponse.jobId, 92];
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, () =>
          HttpResponse.json({
            ...recordingDocumentResponse,
            processingStatus,
            retryable: processingStatus === "DOCUMENT_FAILED",
            retryableJobIds:
              processingStatus === "DOCUMENT_FAILED" ? jobIds : [],
          }),
        ),
        http.post(JOB_RETRY_REQUEST, ({ params }) => {
          retriedJobIds.push(String(params.jobId));
          processingStatus = "PROCESSING";
          return HttpResponse.json(documentGenerationJobRetryResponse, {
            status: 202,
          });
        }),
      );
      renderViewer();

      await expectCardTitles(recordingDocuments);
      fireEvent.click(
        within(getRelatedSection()).getByRole("button", { name: "다시 시도" }),
      );

      expect(
        await screen.findByRole("heading", {
          name: "회의 내용을 바탕으로 문서를 정리하고 있어요",
        }),
      ).toBeInTheDocument();
      expect(retriedJobIds.sort()).toEqual(jobIds.map(String).sort());
    });

    it("다시 시도할 수 없는 문서 생성 실패의 실패 카드에는 「다시 시도」를 두지 않는다", async () => {
      respondWithRecordingDocument({
        processingStatus: "DOCUMENT_FAILED",
        retryable: false,
        retryableJobIds: [],
      });
      renderViewer();

      await expectCardTitles(recordingDocuments);

      expect(
        within(getRelatedSection()).getByText(DOCUMENT_FAILED_TITLE),
      ).toBeInTheDocument();
      expect(
        within(getRelatedSection()).queryByRole("button", {
          name: "다시 시도",
        }),
      ).not.toBeInTheDocument();
    });

    it("만든 문서가 하나도 없으면 머리와 실패 카드만 보여 주고 문서 수를 두지 않는다", async () => {
      respondWithRecordingDocument({
        processingStatus: "DOCUMENT_FAILED",
        retryable: true,
        retryableJobIds: [documentGenerationJobRetryResponse.jobId],
      });
      mockServer.use(
        http.get(DOCUMENTS_REQUEST, () =>
          HttpResponse.json({
            topics: [],
            items: [],
            nextCursor: null,
          } satisfies DocumentsResponse),
        ),
      );
      renderViewer();

      const section = await screen.findByRole("region", {
        name: RELATED_HEADING,
      });
      const items = await within(section).findAllByRole("listitem");

      expect(items).toHaveLength(1);
      expect(items[0]).toHaveTextContent(DOCUMENT_FAILED_TITLE);
      expect(within(section).queryByText("0")).not.toBeInTheDocument();
    });

    it("정리할 내용이 없으면 녹음 음성과 「변환된 음성 기록이 없어요.」만 두고 요약 · 문서 목록을 두지 않는다", async () => {
      respondWithRecordingDocument({
        processingStatus: "NO_CONTENT",
        meetingSummary: null,
      });
      renderViewer();

      expect(
        await screen.findByText("변환된 음성 기록이 없어요."),
      ).toBeInTheDocument();
      expect(
        await screen.findByRole("group", { name: "녹음 음성" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: RELATED_HEADING }),
      ).not.toBeInTheDocument();
      expect(screen.queryByText(NO_SUMMARY_NOTICE)).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: "다시 시도" }),
      ).not.toBeInTheDocument();
    });
  });

  describe("녹음 다시 듣기 (STT-R50)", () => {
    let isPaused = true;

    beforeEach(() => {
      isPaused = true;
      // jsdom은 음성을 재생하지 않아, 재생 · 일시정지 · 불러오기를 브라우저처럼 흉내 내요
      vi.spyOn(HTMLMediaElement.prototype, "paused", "get").mockImplementation(
        () => isPaused,
      );
      vi.spyOn(HTMLMediaElement.prototype, "play").mockImplementation(
        function play(this: HTMLMediaElement) {
          isPaused = false;
          this.dispatchEvent(new Event("play"));
          return Promise.resolve();
        },
      );
      vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(
        function pause(this: HTMLMediaElement) {
          isPaused = true;
          this.dispatchEvent(new Event("pause"));
        },
      );
      vi.spyOn(HTMLMediaElement.prototype, "load").mockImplementation(() => {});
    });

    const findPlayer = () => screen.findByRole("group", { name: "녹음 음성" });

    it("처음에는 0초와 전체 길이를 보여 주고, 재생을 누르면 재생하고 다시 누르면 멈춘다", async () => {
      renderViewer();
      const player = within(await findPlayer());

      expect(
        await player.findByText(
          formatRecordingTime(expectedAudio.recordingDurationMillis / 1000),
        ),
      ).toBeInTheDocument();
      expect(player.getByText(formatRecordingTime(0))).toBeInTheDocument();

      fireEvent.click(player.getByRole("button", { name: "재생" }));
      expect(
        player.getByRole("button", { name: "일시정지" }),
      ).toBeInTheDocument();

      fireEvent.click(player.getByRole("button", { name: "일시정지" }));
      expect(player.getByRole("button", { name: "재생" })).toBeInTheDocument();
    });

    it("위치 막대를 옮기면 그 위치로 옮기고 지금 위치를 바꾼다", async () => {
      renderViewer();
      const player = within(await findPlayer());
      const slider = await player.findByRole("slider", { name: "재생 위치" });
      const targetMillis = 754_000;

      fireEvent.change(slider, { target: { value: String(targetMillis) } });

      expect(
        player.getByText(formatRecordingTime(targetMillis / 1000)),
      ).toBeInTheDocument();
      expect(slider).toHaveAttribute(
        "aria-valuetext",
        `${formatRecordingTime(targetMillis / 1000)} / ${formatRecordingTime(
          expectedAudio.recordingDurationMillis / 1000,
        )}`,
      );
    });

    it("재생 주소가 만료됐으면 재생할 때 새 주소를 먼저 받는다", async () => {
      let requestCount = 0;
      mockServer.use(
        http.get(AUDIO_PLAYBACK_REQUEST, () => {
          requestCount += 1;
          return HttpResponse.json({
            ...recordingAudioPlaybackResponse,
            expiresAt:
              requestCount === 1
                ? new Date(Date.now() - 60_000).toISOString()
                : new Date(Date.now() + 60 * 60_000).toISOString(),
          });
        }),
      );
      renderViewer();
      const player = within(await findPlayer());

      fireEvent.click(await player.findByRole("button", { name: "재생" }));

      await waitFor(() => expect(requestCount).toBe(2));
    });

    it("음성이 남아 있지 않은 녹음은 다시 시도 없이 회색 안내만 둔다", async () => {
      mockServer.use(
        http.get(AUDIO_PLAYBACK_REQUEST, () =>
          HttpResponse.json({
            ...recordingAudioPlaybackResponse,
            audioStatus: "NO_AUDIO",
            playbackUrl: null,
            expiresAt: null,
            contentType: null,
          }),
        ),
      );
      renderViewer();
      const player = within(await findPlayer());

      expect(
        await player.findByText(
          "이 녹음은 음성이 남아 있지 않아 들을 수 없어요.",
        ),
      ).toBeInTheDocument();
      expect(
        player.queryByRole("button", { name: "다시 시도" }),
      ).not.toBeInTheDocument();
    });

    it("음성을 아직 합치는 중이면 다시 시도 없이 준비 중이라는 회색 안내만 둔다", async () => {
      mockServer.use(
        http.get(AUDIO_PLAYBACK_REQUEST, () =>
          HttpResponse.json({
            ...recordingAudioPlaybackResponse,
            audioStatus: "PREPARING",
            playbackUrl: null,
            expiresAt: null,
            contentType: null,
          }),
        ),
      );
      renderViewer();
      const player = within(await findPlayer());

      expect(
        await player.findByText(
          "음성을 준비하고 있어요. 잠시 뒤에 들을 수 있어요.",
        ),
      ).toBeInTheDocument();
      expect(
        player.queryByRole("button", { name: "재생" }),
      ).not.toBeInTheDocument();
    });

    it("음성을 불러오지 못하면 그 자리에만 안내와 「다시 시도」를 두고, 누르면 다시 받아 재생 버튼을 보여 준다", async () => {
      mockServer.use(
        http.get(
          AUDIO_PLAYBACK_REQUEST,
          () =>
            HttpResponse.json(
              {
                code: "AUDIO_STORAGE_UNAVAILABLE",
                message: "저장소를 확인하지 못했습니다.",
              },
              { status: 500 },
            ),
          { once: true },
        ),
      );
      renderViewer();
      const player = within(await findPlayer());

      expect(await player.findByRole("alert")).toHaveTextContent(
        "음성을 불러오지 못했어요. 다시 시도해 주세요.",
      );
      expect(
        screen.getByRole("heading", { name: "한눈에 보기" }),
      ).toBeInTheDocument();

      fireEvent.click(player.getByRole("button", { name: "다시 시도" }));

      expect(
        await player.findByRole("button", { name: "재생" }),
      ).toBeInTheDocument();
    });
  });

  describe("녹음 문서를 받기 전", () => {
    it("불러오는 동안에는 불러오고 있다고 알리고, 받으면 녹음 제목으로 바꾼다", async () => {
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, async () => {
          await delay(100);
          return HttpResponse.json(recordingDocumentResponse);
        }),
      );
      renderViewer();

      expect(
        screen.getByRole("region", { name: "녹음 문서를 불러오고 있어요" }),
      ).toHaveAttribute("aria-busy", "true");

      expect(await findTitle()).toBeInTheDocument();
      expect(
        screen.queryByRole("region", { name: "녹음 문서를 불러오고 있어요" }),
      ).not.toBeInTheDocument();
    });
  });

  describe("녹음 문서를 불러오지 못할 때", () => {
    it("없는 녹음(404)이면 불러오지 못했다고 알리고, 「다시 시도」로 다시 조회한다", async () => {
      let requestCount = 0;
      mockServer.use(
        http.get(RECORDING_DOCUMENT_REQUEST, () => {
          requestCount += 1;
          return HttpResponse.json(
            {
              code: "RECORDING_NOT_FOUND",
              message: "녹음 세션을 찾을 수 없습니다.",
            },
            { status: 404 },
          );
        }),
      );
      renderViewer();

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "녹음을 불러오지 못했어요",
      );

      fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

      await waitFor(() => expect(requestCount).toBe(2));
    });

    it("로그인이 풀렸으면(401) 로그인 화면으로 보낸다", async () => {
      mockServer.use(
        http.get(
          RECORDING_DOCUMENT_REQUEST,
          () => new HttpResponse(null, { status: 401 }),
        ),
      );
      renderViewer();

      expect(await screen.findByText("로그인 화면")).toBeInTheDocument();
    });
  });
});
