import { describe, expect, it } from "vitest";

import { parseDocumentSections } from "./parseDocumentSections";

describe("parseDocumentSections", () => {
  it("결정 · 보류 · 미결정 · 할 일 구역의 항목을 나눠 돌려준다", () => {
    const content = [
      "## 핵심 요약",
      "요약 문장",
      "## 결정",
      "- 로그인은 구글로 한다",
      "- 배포는 금요일에 한다",
      "## 보류",
      "- 유료 전환은 사용자가 100명을 넘으면 본다",
      "## 미결정",
      "1. 알림 채널",
      "## 할 일",
      "* 시안 만들기",
      "## 배경",
      "- 세지 않는 구역",
    ].join("\n");

    expect(parseDocumentSections(content)).toEqual({
      decisions: ["로그인은 구글로 한다", "배포는 금요일에 한다"],
      holds: ["유료 전환은 사용자가 100명을 넘으면 본다"],
      undecided: ["알림 채널"],
      todos: ["시안 만들기"],
    });
  });

  it("목록이 없는 구역은 문장 줄을 항목으로 센다", () => {
    const content = "## 결정\n문장으로만 적은 결정\n\n두 번째 결정";

    expect(parseDocumentSections(content).decisions).toEqual([
      "문장으로만 적은 결정",
      "두 번째 결정",
    ]);
  });

  it("목록과 문장이 섞이면 목록 항목만 센다", () => {
    const content = "## 할 일\n앞 문장\n- 진짜 할 일";

    expect(parseDocumentSections(content).todos).toEqual(["진짜 할 일"]);
  });

  it("구역이 없으면 빈 배열을 돌려준다", () => {
    expect(parseDocumentSections("## 핵심 요약\n문장")).toEqual({
      decisions: [],
      holds: [],
      undecided: [],
      todos: [],
    });
  });
});
