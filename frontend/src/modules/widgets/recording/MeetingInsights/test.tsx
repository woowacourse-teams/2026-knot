import { exampleMeetingInsightsDataset } from "@api/mock/responses/meetingInsights";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import MeetingInsights from ".";
import { LOCAL_DATASET_PATH } from "./model/useMeetingInsightsDataset";

const renderMeetingInsights = () =>
  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={new QueryClient()}>
        <MeetingInsights />
      </QueryClientProvider>
    </ThemeProvider>,
  );

describe("MeetingInsights", () => {
  beforeEach(() => {
    mockServer.use(
      http.get(`*${LOCAL_DATASET_PATH}`, () =>
        HttpResponse.json(exampleMeetingInsightsDataset),
      ),
    );
  });

  it("받은 데이터의 출처와 회의 수를 보여 준다", async () => {
    renderMeetingInsights();

    expect(
      await screen.findByText(/가상 예시 데이터, 회의 3개/),
    ).toBeInTheDocument();
  });

  it("실제 데이터 파일이 없으면 가상 예시로 그린다", async () => {
    mockServer.use(
      http.get(`*${LOCAL_DATASET_PATH}`, () =>
        HttpResponse.text("", { status: 404 }),
      ),
    );
    renderMeetingInsights();

    expect(await screen.findByText(/가상 예시 데이터/)).toBeInTheDocument();
  });

  it("처음에는 요약이 있는 회의의 구조 지도를 보여 준다", async () => {
    renderMeetingInsights();

    expect(
      await screen.findByText("결과 카드부터 만들고 공개 범위는 다음에 정한다"),
    ).toBeInTheDocument();
  });

  it("회의를 고르면 회의 단위 값이 바뀐다", async () => {
    renderMeetingInsights();

    const picker = await screen.findByLabelText("회의 고르기");

    fireEvent.change(picker, { target: { value: "102" } });

    const section = screen.getByRole("region", { name: "회의 결과" });

    expect(
      within(section).getByText(
        "요약이 없는 회의라 문서 제목과 결정 항목으로 가지를 만들었어요",
      ),
    ).toBeInTheDocument();
  });

  it("화자 품질 카드를 사람과 말하기 구역의 맨 앞에 둔다", async () => {
    renderMeetingInsights();

    const section = await screen.findByRole("region", {
      name: "사람과 말하기",
    });
    const [firstCardTitle] = within(section).getAllByRole("heading", {
      level: 3,
    });

    expect(firstCardTitle).toHaveTextContent("화자 번호 품질");
  });
});
