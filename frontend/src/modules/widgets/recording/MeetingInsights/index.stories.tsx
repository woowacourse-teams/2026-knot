import { exampleMeetingInsightsDataset } from "@api/mock/responses/meetingInsights";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";

import MeetingInsights from ".";
import { LOCAL_DATASET_PATH } from "./model/useMeetingInsightsDataset";

/** 회의 인포그래픽 시안. 가상 예시 회의 3개로 그려요 */
const meta = {
  title: "widgets/recording/MeetingInsights",
  component: MeetingInsights,
  parameters: {
    layout: "fullscreen",
    msw: {
      handlers: [
        http.get(`*${LOCAL_DATASET_PATH}`, () =>
          HttpResponse.json(exampleMeetingInsightsDataset),
        ),
      ],
    },
  },
} satisfies Meta<typeof MeetingInsights>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 팀이 기획 회의에서 보는 기본 화면이에요 */
export const Default: Story = {};
