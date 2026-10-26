import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  REPORT_MARKER,
  buildGrepPattern,
  checkRelatedQa,
  collectE2eTests,
  mergeOutcome,
  parseRelatedQa,
  parseTestIds,
  renderReport,
  runCheck,
} from "./qa-link.mjs";

/** status가 있으면 실행 결과, 없으면 `--list` 목록의 테스트처럼 만든다 */
const spec = (title, tags, { line = 10, statuses = [] } = {}) => ({
  title,
  tags,
  file: "page.test.ts",
  line,
  tests: statuses.length > 0 ? statuses.map((status) => ({ status, results: [{}] })) : [{ results: [] }],
});

const report = (specs, extra = {}) => ({
  suites: [{ title: "page.test.ts", file: "page.test.ts", specs: [], suites: [{ title: "여정", specs }] }],
  ...extra,
});

const bodyWith = (section) =>
  `## 관련 이슈\n\n- #843\n\n## 관련 QA\n\n<!-- 형식 안내 -->\n${section}\n\n## 작업 내용\n\n내용\n`;

describe("parseRelatedQa", () => {
  it("관련 QA 절이 없으면 항목이 없다", () => {
    assert.deepEqual(parseRelatedQa("## 작업 내용\n\n- STT-R25"), { items: [], errors: [] });
  });

  it("주석만 있는 템플릿 절은 항목이 없는 것으로 본다", () => {
    const body = "## 관련 QA\n\n<!--\n- STT-R25 처럼 적어요\n-->\n\n## 작업 내용\n";
    assert.deepEqual(parseRelatedQa(body).items, []);
  });

  it("ID 줄과 E2E 제외 줄을 읽고, 다음 절에서 멈춘다", () => {
    const { items, errors } = parseRelatedQa(
      bodyWith("- STT-R25\n- STT-R28 (E2E 제외: 실제 서버 응답 필요)\r\n- [CONF-R2, CONF-R3] 문서 넘기기"),
    );
    assert.deepEqual(errors, []);
    assert.deepEqual(items, [
      { id: "STT-R25", excluded: false, reason: "" },
      { id: "STT-R28", excluded: true, reason: "실제 서버 응답 필요" },
      { id: "CONF-R2", excluded: false, reason: "" },
      { id: "CONF-R3", excluded: false, reason: "" },
    ]);
  });

  it("ID를 읽지 못한 줄과 이유 없는 E2E 제외는 고칠 것으로 알린다", () => {
    const { items, errors } = parseRelatedQa(bodyWith("- 녹음 확인\n- STT-R28 (E2E 제외)"));
    assert.equal(errors.length, 2);
    assert.match(errors[0], /QA ID를 읽지 못했어요/);
    assert.match(errors[1], /E2E 제외 이유가 없어요/);
    assert.deepEqual(items, [{ id: "STT-R28", excluded: true, reason: "" }]);
  });

  it("같은 ID를 두 번 적으면 한 번만 본다", () => {
    const { items } = parseRelatedQa(bodyWith("- STT-R25\n- STT-R25"));
    assert.equal(items.length, 1);
  });
});

describe("parseTestIds", () => {
  it("이름 앞 대괄호의 ID를 쉼표로 나눈다", () => {
    assert.deepEqual(parseTestIds("[CONF-R2, CONF-R3] 문서를 넘겨 본다"), ["CONF-R2", "CONF-R3"]);
  });

  it("대괄호가 없거나 ID 모양이 아니면 빈 배열이다", () => {
    assert.deepEqual(parseTestIds("초대 링크로 참여한다"), []);
    assert.deepEqual(parseTestIds("[임시] 확인"), []);
    assert.deepEqual(parseTestIds("문서 [STT-R25] 확인"), []);
  });
});

describe("mergeOutcome", () => {
  it("실패가 하나라도 있으면 실패, 그다음은 재시도 후 통과, 모두 건너뛰면 건너뜀이다", () => {
    assert.equal(mergeOutcome(["expected", "unexpected", "flaky"]), "failed");
    assert.equal(mergeOutcome(["expected", "flaky"]), "flaky");
    assert.equal(mergeOutcome(["skipped", "skipped"]), "skipped");
    assert.equal(mergeOutcome(["expected", "skipped"]), "passed");
    assert.equal(mergeOutcome([]), null);
  });
});

describe("collectE2eTests", () => {
  it("태그에 @를 붙이고, 같은 파일과 줄의 같은 테스트는 결과를 합쳐 하나로 본다", () => {
    const tests = collectE2eTests(
      report([
        spec("[STT-R25] 녹음", ["core"], { statuses: ["expected"] }),
        spec("[STT-R25] 녹음", ["core"], { statuses: ["flaky"] }),
      ]),
    );
    assert.deepEqual(tests, [
      { title: "[STT-R25] 녹음", file: "page.test.ts", line: 10, tags: ["@core"], ids: ["STT-R25"], outcome: "flaky" },
    ]);
  });

  it("실행하지 않은 목록의 테스트는 결과가 없다", () => {
    const [test] = collectE2eTests(report([spec("[STT-R25] 녹음", ["core"])]));
    assert.equal(test.outcome, null);
  });
});

describe("checkRelatedQa", () => {
  const tests = collectE2eTests(report([spec("[STT-R25] 녹음", ["core"])]));
  const check = (section) => checkRelatedQa(parseRelatedQa(bodyWith(section)).items, tests);

  it("E2E가 있는 ID는 통과하고 그 테스트를 함께 돌려준다", () => {
    const { rows, errors } = check("- STT-R25");
    assert.deepEqual(errors, []);
    assert.equal(rows[0].tests[0].title, "[STT-R25] 녹음");
  });

  it("E2E가 없는 ID는 qa-e2e 스킬과 E2E 제외 방법을 알리며 실패한다", () => {
    const { errors } = check("- STT-R28");
    assert.equal(errors.length, 1);
    assert.match(errors[0], /qa-e2e 스킬/);
    assert.match(errors[0], /- STT-R28 \(E2E 제외: 이유\)/);
  });

  it("E2E 제외로 적은 ID는 E2E가 없어도 통과한다", () => {
    assert.deepEqual(check("- STT-R28 (E2E 제외: 실제 서버 응답 필요)").errors, []);
  });
});

describe("buildGrepPattern", () => {
  it("관련 QA가 없으면 @core만 돌린다", () => {
    assert.equal(buildGrepPattern([]), "@core");
  });

  it("E2E가 있는 관련 QA ID를 더하고, 비슷한 ID와 섞이지 않는다", () => {
    const pattern = buildGrepPattern([
      { id: "STT-R2", tests: [{}] },
      { id: "STT-R28", tests: [] },
    ]);
    const matches = (title) => new RegExp(pattern).test(title);

    assert.equal(pattern.startsWith("@core|"), true);
    assert.equal(matches("[STT-R2] 일시정지"), true);
    assert.equal(matches("[CONF-R2, STT-R2] 문서"), true);
    assert.equal(matches("[STT-R25] 녹음"), false);
    assert.equal(matches("[STT-R28] 재접속"), false);
  });
});

describe("runCheck", () => {
  const listReport = report([spec("[STT-R25] 녹음", ["core"]), spec("초대", ["core"], { line: 20 })]);
  const run = (prBody) => runCheck({ prBody, listReport });

  it("관련 QA가 없으면 통과하고 @core만 돌린다", () => {
    const result = run("## 작업 내용\n\n내용");
    assert.equal(result.ok, true);
    assert.equal(result.grep, "@core");
    assert.match(result.text, /관련 QA가 없어/);
  });

  it("E2E가 있는 관련 QA는 통과하고 grep 패턴에 ID를 더한다", () => {
    const result = run(bodyWith("- STT-R25"));
    assert.equal(result.ok, true);
    assert.match(result.grep, /STT-R25/);
    assert.match(result.text, /\| STT-R25 \| \[STT-R25\] 녹음 \(`page.test.ts:10`\) \| ✅ E2E 있음 \|/);
  });

  it("E2E가 없으면 실패하고 고칠 것을 보여 준다", () => {
    const result = run(bodyWith("- STT-R28"));
    assert.equal(result.ok, false);
    assert.equal(result.grep, "@core");
    assert.match(result.text, /고칠 것:/);
  });

  it("E2E 제외는 통과하고, 이유가 없으면 실패한다", () => {
    assert.equal(run(bodyWith("- STT-R28 (E2E 제외: 실제 서버 응답 필요)")).ok, true);
    const result = run(bodyWith("- STT-R28 (E2E 제외)"));
    assert.equal(result.ok, false);
    assert.match(result.errors[0], /E2E 제외 이유가 없어요/);
  });
});

describe("renderReport", () => {
  const resultReport = report(
    [
      spec("[STT-R25] 녹음", ["core"], { statuses: ["expected"] }),
      spec("[SEARCH-R6] 질문", ["core"], { line: 20, statuses: ["flaky"] }),
      spec("[CONF-R2, CONF-R3] 문서", [], { line: 30, statuses: ["unexpected"] }),
    ],
    { stats: { expected: 1, unexpected: 1, flaky: 1, skipped: 0 }, errors: [] },
  );
  const run = {
    serverUrl: "https://github.com",
    repository: "woowacourse-teams/2026-Knot",
    runId: "123",
    sha: "abcdef1234567",
  };

  it("맨 위에 숨은 표식과 커밋, 실행 수를 쓴다", () => {
    const markdown = renderReport({ prBody: "", resultReport, run });
    assert.equal(markdown.startsWith(`${REPORT_MARKER}\n## Frontend E2E 결과 (\`abcdef1\` 기준)`), true);
    assert.match(markdown, /3번 실행 중 1번 통과, 1번 실패, 1번 재시도 후 통과/);
  });

  it("관련 QA 표에 테스트별 결과와 E2E 제외 이유를 쓴다", () => {
    const markdown = renderReport({
      prBody: bodyWith("- CONF-R2\n- STT-R28 (E2E 제외: 실제 서버 응답 필요)"),
      resultReport,
      run,
    });
    assert.match(markdown, /\| CONF-R2 \| \[CONF-R2, CONF-R3\] 문서 \(`page.test.ts:30`\) \| ❌ 실패 \|/);
    assert.match(markdown, /\| STT-R28 \| - \| ⏭️ E2E 제외: 실제 서버 응답 필요 \|/);
  });

  it("핵심 여정 표에는 @core 테스트만 결과와 함께 쓴다", () => {
    const markdown = renderReport({ prBody: "", resultReport, run });
    assert.match(markdown, /\| \[STT-R25\] 녹음 \(`page.test.ts:10`\) \| ✅ 통과 \|/);
    assert.match(markdown, /\| \[SEARCH-R6\] 질문 \(`page.test.ts:20`\) \| ⚠️ 재시도 후 통과 \|/);
    assert.doesNotMatch(markdown.split("### 핵심 여정")[1], /CONF-R2/);
  });

  it("E2E가 없는 관련 QA는 고칠 것으로 알린다", () => {
    const markdown = renderReport({ prBody: bodyWith("- STT-R99"), resultReport, run });
    assert.match(markdown, /\| STT-R99 \| - \| ❌ E2E 없음 \|/);
    assert.match(markdown, /### 고칠 것\n\n- 관련 QA STT-R99: 이 ID를 붙인 E2E가 없어요/);
  });

  it("직접 보는 법에 실행 주소, artifact, 로컬 명령을 쓴다", () => {
    const markdown = renderReport({
      prBody: bodyWith("- CONF-R2"),
      resultReport,
      run: { ...run, artifactUrl: "https://github.com/woowacourse-teams/2026-Knot/actions/runs/123/artifacts/9" },
    });
    assert.match(markdown, /\[이 실행의 artifact 「playwright-report」\]\(https:\/\/github\.com\/.*\/artifacts\/9\)/);
    assert.match(markdown, /\[GitHub Actions 실행\]\(https:\/\/github\.com\/woowacourse-teams\/2026-Knot\/actions\/runs\/123\)/);
    assert.match(markdown, /`pnpm test:e2e:ui`/);
    assert.match(markdown, /`pnpm test:e2e -g "CONF-R2"`/);
    assert.match(markdown, /npx playwright show-report/);
  });

  it("artifact 주소가 없으면 실행 주소의 artifacts로 잇고, 실행 정보가 없으면 로컬 명령만 쓴다", () => {
    assert.match(renderReport({ prBody: "", resultReport, run }), /actions\/runs\/123#artifacts/);
    const local = renderReport({ prBody: "", resultReport });
    assert.doesNotMatch(local, /GitHub Actions 실행/);
    assert.match(local, /pnpm test:e2e -g "STT-R25"/);
  });

  it("결과 파일이 없으면 결과 없음으로 알리고 E2E 없음으로 몰지 않는다", () => {
    const markdown = renderReport({ prBody: bodyWith("- STT-R25"), resultReport: null, run });
    assert.match(markdown, /결과 파일을 남기지 못했어요/);
    assert.match(markdown, /\| STT-R25 \| - \| 결과 없음 \|/);
    assert.doesNotMatch(markdown, /### 고칠 것/);
  });

  it("테스트 밖의 오류(웹 서버 실패 등)를 첫 줄만 알린다", () => {
    const markdown = renderReport({
      prBody: "",
      resultReport: { ...resultReport, errors: [{ message: "Timed out waiting 120000ms from config.webServer.\nstack" }] },
      run,
    });
    assert.match(markdown, /> E2E가 테스트 밖에서 실패했어요: Timed out waiting 120000ms from config.webServer\./);
    assert.doesNotMatch(markdown, /stack/);
  });
});
