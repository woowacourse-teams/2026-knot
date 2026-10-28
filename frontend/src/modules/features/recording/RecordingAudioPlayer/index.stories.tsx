import { RECORDING_AUDIO_PLAYBACK_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/audioPlayback";
import { recordingAudioPlaybackResponse } from "@api/mock/responses/recordingDocument";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";

import RecordingAudioPlayer from ".";

const WORKSPACE_ID = 1;
const RECORDING_ID = recordingAudioPlaybackResponse.recordingId;
const AUDIO_PLAYBACK_REQUEST = `*${RECORDING_AUDIO_PLAYBACK_API_PATH(WORKSPACE_ID, RECORDING_ID)}`;

// 응답을 바꾸는 스토리만 따로 그려 문서 페이지에서 다른 스토리의 응답과 섞이지 않게 해요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 160 } };

/** 들을 수 있는 음성이 없을 때의 응답. 재생 주소 · 만료 시각 · 형식은 비어 와요 */
const noPlayableAudio = {
  playbackUrl: null,
  expiresAt: null,
  contentType: null,
};

/**
 * 녹음 문서 맨 위에서 그 녹음의 음성을 다시 듣는 막대예요. 재생 · 일시정지 버튼, 지금 위치, 위치 막대, 전체 길이가 한 줄에 있어요.
 * 문서 정리를 마친 녹음(완료 · 문서 생성 실패 · 정리할 내용 없음)에만 놓여요.
 *
 * **동작 규칙**
 * - 재생을 누르면 재생하고, 다시 누르면 멈춰요. 위치 막대를 끌거나 누르거나 방향키로 옮기면 그 위치부터 들려요.
 * - 전체 길이는 일시정지를 뺀 녹음 길이예요. 음성 파일에 길이가 없을 때도 같은 값을 보여 주려고 서버가 준 녹음 길이를 써요.
 * - 재생 주소에는 만료 시각이 있어요. 만료된 뒤 재생을 누르면 새 주소를 먼저 받고 멈췄던 위치부터 이어서 들려요.
 * - 지난 정리 정책으로 음성 파일이 지워진 녹음은 실패가 아니라서 다시 시도 없이 회색 안내만 있어요.
 * - 음성을 불러오지 못하면 이 자리에만 안내와 「다시 시도」가 있고, 녹음 제목 · 요약 · 전사는 그대로 읽을 수 있어요.
 * - 듣기는 녹음 · 전사 · 요약 · 처리 상태와 문서 확인 기록을 바꾸지 않아요.
 */
const meta = {
  title: "Recording/RecordingAudioPlayer",
  component: RecordingAudioPlayer,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5367-1768",
    },
  },
  decorators: [
    (Story) => (
      <MemoryRouter
        initialEntries={[
          getRouterPath({
            routeKey: "RECORDING_DOCUMENT",
            params: {
              workspaceId: String(WORKSPACE_ID),
              recordingId: String(RECORDING_ID),
            },
          }),
        ]}
      >
        <Routes>
          <Route path={PATH_ROUTE.RECORDING_DOCUMENT} element={<Story />} />
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RecordingAudioPlayer>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 들을 수 있는 녹음이에요. 처음에는 멈춰 있고 0초부터 시작해요. 이 스토리의 음성은 10초짜리 무음이에요 */
export const Default: Story = {};

/** 음성 API만 미지원일 때예요. 녹음 제목·요약·원문은 계속 읽고, 지원 여부를 다시 확인할 수 있어요. */
export const UnsupportedApi: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 300 } },
    msw: { handlers: { audioPlayback: http.get(AUDIO_PLAYBACK_REQUEST, () =>
      HttpResponse.json({ code: "REQUEST_PATH_NOT_FOUND" }, { status: 404 }),
    ) } },
  },
};

/** 지난 정리 정책으로 음성 파일이 지워진 녹음이에요. 다시 시도 없이 회색 안내만 있어요 */
export const NoAudio: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(AUDIO_PLAYBACK_REQUEST, () =>
          HttpResponse.json({
            ...recordingAudioPlaybackResponse,
            ...noPlayableAudio,
            audioStatus: "NO_AUDIO",
          }),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음 조각을 아직 하나의 음성으로 합치는 중이에요. 시안이 없어 음성 없음과 같은 모양에 문구만 달라요 */
export const Preparing: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(AUDIO_PLAYBACK_REQUEST, () =>
          HttpResponse.json({
            ...recordingAudioPlaybackResponse,
            ...noPlayableAudio,
            audioStatus: "PREPARING",
          }),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 음성을 불러오지 못했을 때예요. 예: 저장소를 잠시 확인하지 못했을 때 */
export const LoadFailed: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(AUDIO_PLAYBACK_REQUEST, () =>
          HttpResponse.json(
            {
              code: "AUDIO_STORAGE_UNAVAILABLE",
              message: "저장소를 확인하지 못했습니다.",
            },
            { status: 500 },
          ),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};
