import { GetMeResponseDto } from "@api/dto/auth";
import { AUTH_ME_API_PATH } from "@api/fetch/api/v1/auth/me";
import { meResponse } from "@api/mock/responses/auth";
import { mockServer } from "@api/mock/server";
import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { delay, http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";

import RecordingCard from ".";

const expected = new GetMeResponseDto(meResponse);

const renderCard = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <ThemeProvider theme={theme}>
      <QueryClientProvider client={queryClient}>
        <RecordingCard />
      </QueryClientProvider>
    </ThemeProvider>,
  );

  return { queryClient };
};

describe("RecordingCard", () => {
  it("회원 정보 응답의 닉네임으로 녹음 제목을 보여준다", async () => {
    renderCard();

    expect(
      await screen.findByRole("heading", {
        name: `${expected.nickname} 님의 녹음`,
      }),
    ).toBeInTheDocument();
  });

  it("응답 전에는 닉네임 없이 녹음으로 보여준다", () => {
    mockServer.use(
      http.get(`*${AUTH_ME_API_PATH}`, async () => {
        await delay("infinite");
        return HttpResponse.json(meResponse);
      }),
    );
    renderCard();

    expect(screen.getByRole("heading", { name: "녹음" })).toBeInTheDocument();
  });

  it("회원 정보를 못 받으면 닉네임 없이 녹음으로 보여준다", async () => {
    mockServer.use(
      http.get(
        `*${AUTH_ME_API_PATH}`,
        () => new HttpResponse(null, { status: 500 }),
      ),
    );
    const { queryClient } = renderCard();

    // 응답 전에도 같은 제목이라, 요청이 실패로 끝난 뒤의 화면을 확인해요
    await waitFor(() => expect(queryClient.isFetching()).toBe(0));

    expect(screen.getByRole("heading", { name: "녹음" })).toBeInTheDocument();
  });
});
