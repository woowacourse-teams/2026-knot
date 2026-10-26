import { describe, expect, it } from "vitest";

import { getWorkspaceNameErrorMessage } from "./getWorkspaceNameErrorMessage";

const FORMAT_ERROR_MESSAGE = "한글, 영어, 숫자와 공백만 사용할 수 있어요.";

describe("getWorkspaceNameErrorMessage", () => {
  it("한글, 영어, 숫자, 공백을 섞은 이름은 통과한다", () => {
    expect(getWorkspaceNameErrorMessage("노트 Team 2026")).toBeUndefined();
  });

  it("숫자만으로 된 이름도 통과한다", () => {
    expect(getWorkspaceNameErrorMessage("2026")).toBeUndefined();
  });

  it("한글을 조합하는 중인 자모는 통과한다", () => {
    expect(getWorkspaceNameErrorMessage("ㄴ")).toBeUndefined();
  });

  it("빈 값은 아직 입력 전이라 통과한다", () => {
    expect(getWorkspaceNameErrorMessage("")).toBeUndefined();
  });

  it.each(["팀!", "２０２６", "①팀"])(
    "기호, 전각 숫자, 원 숫자가 들면 형식 문구를 돌려준다: %s",
    (name) => {
      expect(getWorkspaceNameErrorMessage(name)).toBe(FORMAT_ERROR_MESSAGE);
    },
  );
});
