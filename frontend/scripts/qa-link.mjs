#!/usr/bin/env node
/**
 * qa-link — PR 본문의 관련 QA와 E2E가 이어져 있는지 보는 PR 관문 스크립트.
 * QA 시트(노션)는 읽지 않는다. 우선순위와 판정의 정본은 노션에만 둔다.
 *
 * 모드:
 * - check(기본): PR_BODY의 「## 관련 QA」 ID마다 「E2E 제외: 이유」가 아니면 그 ID를 이름 앞 대괄호에 붙인 E2E가 있는지 본다.
 *   E2E 목록은 E2E_LIST_FILE이 있으면 그 파일을, 없으면 `playwright test --list --reporter=json` 결과를 읽는다.
 *   돌릴 grep 패턴(`@core`와 E2E가 있는 관련 ID)을 알린다.
 * - `--report`: Playwright JSON 결과(E2E_RESULT_FILE)를 읽어 PR 댓글과 job summary에 쓸 마크다운을 만든다.
 * - `--detect`: PR 본문에 관련 QA 항목이 있는지만 `related=true|false`로 알린다(의존성 설치 전에 쓴다).
 *
 * 출력: GITHUB_OUTPUT이 있으면 `grep=`, `related=` 줄. `--report`는 QA_REPORT_FILE이 있으면 그 파일에, 없으면 표준 출력에 쓴다.
 * 종료 코드: 0 통과, 1 고칠 것이 있음, 2 입력을 읽지 못함.
 */
import { spawnSync } from "node:child_process";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const FRONTEND_DIR = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SKILL_LABEL = "qa-e2e 스킬(`frontend/.claude/skills/qa-e2e/SKILL.md`)";

/** PR 댓글을 다시 찾을 때 쓰는 숨은 표식. 워크플로의 댓글 step과 같은 값이어야 한다 */
export const REPORT_MARKER = "<!-- frontend-e2e:result -->";

const ID = "[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+";
const ID_PATTERN = new RegExp(`^${ID}$`);
const LEADING_IDS = new RegExp(`^\\[?(${ID}(?:\\s*,\\s*${ID})*)\\]?`);
const EXCLUDE_PATTERN = /\(\s*E2E\s*제외\s*(?::\s*([^)]*))?\)/;

const OUTCOME_LABEL = {
  passed: "✅ 통과",
  failed: "❌ 실패",
  flaky: "⚠️ 재시도 후 통과",
  skipped: "⏭️ 건너뜀",
};

const splitIds = (text) => text.split(",").map((id) => id.trim());

/**
 * PR 본문의 「## 관련 QA」 절을 읽는다. 주석(`<!-- -->`)은 빼고 읽는다.
 * 줄 형식: `- STT-R28` 또는 `- STT-R28 (E2E 제외: 실제 서버 응답 필요)`. 쉼표로 여러 ID를 적어도 된다.
 * 절이 없거나 항목이 하나도 없으면 items가 빈 배열이다.
 */
export const parseRelatedQa = (body) => {
  const text = (body ?? "").replace(/<!--[\s\S]*?-->/g, "").replace(/\r/g, "");
  const lines = text.split("\n");
  const start = lines.findIndex((line) => /^##\s+관련 QA\s*$/.test(line.trim()));
  const items = [];
  const errors = [];

  if (start === -1) return { items, errors };

  for (const rawLine of lines.slice(start + 1)) {
    const line = rawLine.trim();
    if (/^#{1,6}\s/.test(line)) break;

    const bullet = line.match(/^[-*]\s+(.*)$/);
    if (!bullet) continue;

    const content = bullet[1].trim();
    const ids = content.match(LEADING_IDS);
    if (!ids) {
      errors.push(
        `관련 QA의 「${line}」 줄에서 QA ID를 읽지 못했어요. \`- STT-R28\` 또는 \`- STT-R28 (E2E 제외: 이유)\` 형식으로 적으세요.`,
      );
      continue;
    }

    const exclude = content.match(EXCLUDE_PATTERN);
    const reason = exclude?.[1]?.trim() ?? "";
    if (exclude && !reason) {
      errors.push(
        `관련 QA의 「${line}」 줄에 E2E 제외 이유가 없어요. \`(E2E 제외: 실제 서버 응답 필요)\`처럼 왜 E2E로 볼 수 없는지 적으세요.`,
      );
    }

    for (const id of splitIds(ids[1])) {
      if (items.some((item) => item.id === id)) continue;
      items.push({ id, excluded: Boolean(exclude), reason });
    }
  }

  return { items, errors };
};

/** 테스트 이름 앞 대괄호의 ID. `[CONF-R2, CONF-R3] ...` → ["CONF-R2", "CONF-R3"] */
export const parseTestIds = (title) => {
  const match = title.match(/^\[([^\]]+)\]/);
  if (!match) return [];
  const ids = splitIds(match[1]);
  return ids.every((id) => ID_PATTERN.test(id)) ? ids : [];
};

/**
 * 한 테스트의 실행 결과들을 하나로 합친다. Playwright 상태(expected, unexpected, flaky, skipped)를 받는다.
 * 한 번이라도 실패하면 실패, 실패 없이 재시도로 통과한 적이 있으면 재시도 후 통과다. 결과가 없으면 null이다.
 */
export const mergeOutcome = (statuses) => {
  if (statuses.length === 0) return null;
  if (statuses.includes("unexpected")) return "failed";
  if (statuses.includes("flaky")) return "flaky";
  if (statuses.every((status) => status === "skipped")) return "skipped";
  return "passed";
};

/**
 * Playwright JSON(`--list` 목록이나 실행 결과)에서 테스트를 꺼낸다.
 * 같은 테스트가 뷰포트별 describe나 `--repeat-each`로 여러 번 나오면 파일, 줄, 이름이 같은 것끼리 하나로 합친다.
 */
export const collectE2eTests = (report) => {
  const tests = new Map();

  const walk = (suite) => {
    for (const spec of suite.specs ?? []) {
      const key = `${spec.file}:${spec.line}:${spec.title}`;
      if (!tests.has(key)) {
        tests.set(key, {
          title: spec.title,
          file: spec.file,
          line: spec.line,
          tags: (spec.tags ?? []).map((tag) => (tag.startsWith("@") ? tag : `@${tag}`)),
          ids: parseTestIds(spec.title),
          statuses: [],
        });
      }
      const test = tests.get(key);
      for (const run of spec.tests ?? []) {
        if (run.results?.length > 0) test.statuses.push(run.status);
      }
    }
    for (const child of suite.suites ?? []) walk(child);
  };

  for (const suite of report.suites ?? []) walk(suite);
  return [...tests.values()].map(({ statuses, ...test }) => ({
    ...test,
    outcome: mergeOutcome(statuses),
  }));
};

const missingE2eError = (id) =>
  `관련 QA ${id}: 이 ID를 붙인 E2E가 없어요. ${SKILL_LABEL}을 따라 이름 앞에 \`[${id}]\`을 붙인 E2E를 쓰세요. 실제 서버, 실기기, 사람의 판단이 필요해 E2E로 볼 수 없는 행이면 PR 본문 관련 QA에 \`- ${id} (E2E 제외: 이유)\`로 적으세요.`;

/** 관련 QA의 ID마다 그 ID를 붙인 E2E를 찾는다. 제외가 아닌데 E2E가 없으면 고칠 것으로 알린다 */
export const checkRelatedQa = (items, tests) => {
  const errors = [];
  const rows = items.map((item) => {
    const matched = tests.filter((test) => test.ids.includes(item.id));
    if (!item.excluded && matched.length === 0) errors.push(missingE2eError(item.id));
    return { ...item, tests: matched };
  });
  return { rows, errors };
};

const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** 워크플로가 돌릴 grep 패턴. 핵심 여정(@core)에 관련 QA 중 E2E가 있는 ID를 더한다 */
export const buildGrepPattern = (rows) =>
  [
    "@core",
    ...rows
      .filter(({ tests }) => tests.length > 0)
      .map(({ id }) => `(?<![\\w-])${escapeRegExp(id)}(?![\\w-])`),
  ].join("|");

const cell = (text) => String(text ?? "-").replace(/\|/g, "\\|").replace(/\n/g, " ");

const describeTest = ({ title, file, line }) => `${title} (\`${file}:${line}\`)`;

const excludedText = (reason) => `⏭️ E2E 제외: ${reason || "이유 없음"}`;

/** check 모드에서 사람이 읽을 결과. 로컬 터미널과 CI 로그에 같이 쓴다 */
const renderCheck = ({ rows, errors, grep }) => {
  const lines = [];

  if (rows.length === 0) {
    lines.push("PR 본문에 관련 QA가 없어 핵심 여정 E2E(@core)만 돌려요.");
  } else {
    lines.push(
      "| 관련 QA ID | E2E 테스트 | 상태 |",
      "| --- | --- | --- |",
      ...rows.map(({ id, excluded, reason, tests }) => {
        const status = excluded
          ? excludedText(reason)
          : tests.length > 0
            ? "✅ E2E 있음"
            : "❌ E2E 없음";
        const testText = tests.length > 0 ? tests.map(describeTest).join("<br>") : "-";
        return `| ${cell(id)} | ${cell(testText)} | ${cell(status)} |`;
      }),
    );
  }

  if (errors.length > 0) lines.push("", "고칠 것:", ...errors.map((error) => `- ${error}`));
  lines.push("", `돌릴 E2E grep 패턴: ${grep}`);
  return lines.join("\n");
};

/** check 모드. 입력을 모두 받아 결과를 돌려준다. 파일과 환경 변수는 읽지 않는다 */
export const runCheck = ({ prBody, listReport }) => {
  const parsed = parseRelatedQa(prBody);
  const related = checkRelatedQa(parsed.items, collectE2eTests(listReport));
  const errors = [...parsed.errors, ...related.errors];
  const grep = buildGrepPattern(related.rows);

  return {
    ok: errors.length === 0,
    errors,
    grep,
    text: renderCheck({ rows: related.rows, errors, grep }),
  };
};

const outcomeText = (outcome) => OUTCOME_LABEL[outcome] ?? "결과 없음";

const summarizeStats = (stats) => {
  if (!stats) return null;
  const { expected = 0, unexpected = 0, flaky = 0, skipped = 0 } = stats;
  const total = expected + unexpected + flaky + skipped;
  const parts = [
    `${total}번 실행 중 ${expected}번 통과`,
    `${unexpected}번 실패`,
    `${flaky}번 재시도 후 통과`,
  ];
  if (skipped > 0) parts.push(`${skipped}번 건너뜀`);
  return parts.join(", ");
};

/** 관련 QA 표의 줄. 한 ID에 테스트가 여럿이면 테스트마다 한 줄이다 */
const relatedQaLines = (items, tests, hasResult) =>
  items.flatMap(({ id, excluded, reason }) => {
    if (excluded) return [`| ${cell(id)} | - | ${cell(excludedText(reason))} |`];

    const matched = tests.filter((test) => test.ids.includes(id));
    if (matched.length === 0) {
      return [`| ${cell(id)} | - | ${hasResult ? "❌ E2E 없음" : "결과 없음"} |`];
    }
    return matched.map(
      (test) => `| ${cell(id)} | ${cell(describeTest(test))} | ${outcomeText(test.outcome)} |`,
    );
  });

/**
 * report 모드. Playwright JSON 결과로 PR 댓글 마크다운을 만든다. 파일과 환경 변수는 읽지 않는다.
 * resultReport가 null이면 E2E가 결과를 남기지 못한 것으로 보고 그렇게 알린다.
 * run은 GitHub Actions 실행 정보(serverUrl, repository, runId, artifactUrl, sha)이고, 없으면 원격 링크를 빼고 쓴다.
 */
export const renderReport = ({ prBody, resultReport, run = {} }) => {
  const parsed = parseRelatedQa(prBody);
  const hasResult = Boolean(resultReport);
  const tests = hasResult ? collectE2eTests(resultReport) : [];
  const coreTests = tests.filter((test) => test.tags.includes("@core"));
  const errors = [...parsed.errors];
  if (hasResult) errors.push(...checkRelatedQa(parsed.items, tests).errors);

  const runUrl =
    run.serverUrl && run.repository && run.runId
      ? `${run.serverUrl}/${run.repository}/actions/runs/${run.runId}`
      : null;
  const sha = run.sha ? ` (\`${run.sha.slice(0, 7)}\` 기준)` : "";

  const lines = [REPORT_MARKER, `## Frontend E2E 결과${sha}`, ""];

  if (!hasResult) {
    lines.push(
      "E2E가 결과 파일을 남기지 못했어요. 실행 로그에서 E2E step이 왜 멈췄는지 확인하세요.",
      "",
    );
  } else {
    const stats = summarizeStats(resultReport.stats);
    if (stats) lines.push(stats, "");
    for (const { message } of resultReport.errors ?? []) {
      lines.push(`> E2E가 테스트 밖에서 실패했어요: ${cell(message?.split("\n")[0])}`, "");
    }
  }

  lines.push("### 관련 QA", "");
  if (parsed.items.length === 0) {
    lines.push("PR 본문에 관련 QA가 없어 핵심 여정 E2E(`@core`)만 돌렸어요.", "");
  } else {
    lines.push(
      "| 관련 QA ID | E2E 테스트 | 결과 |",
      "| --- | --- | --- |",
      ...relatedQaLines(parsed.items, tests, hasResult),
      "",
    );
  }

  lines.push("### 핵심 여정 (`@core`)", "");
  if (coreTests.length === 0) {
    lines.push(hasResult ? "돌린 핵심 여정 E2E가 없어요." : "결과 없음", "");
  } else {
    lines.push(
      "| E2E 테스트 | 결과 |",
      "| --- | --- |",
      ...coreTests.map((test) => `| ${cell(describeTest(test))} | ${outcomeText(test.outcome)} |`),
      "",
    );
  }

  if (errors.length > 0) {
    lines.push("### 고칠 것", "", ...errors.map((error) => `- ${error}`), "");
  }

  const exampleId = parsed.items.find(({ excluded }) => !excluded)?.id ?? "STT-R25";
  lines.push("### 직접 보는 법", "");
  if (runUrl) {
    lines.push(
      `- 보고서: [이 실행의 artifact 「playwright-report」](${run.artifactUrl ?? `${runUrl}#artifacts`})를 내려받아 압축을 푼 뒤 \`npx playwright show-report <푼 폴더>\`로 열어요. 실패한 테스트는 스크린샷과 영상이, 재시도한 테스트는 트레이스가 함께 들어 있어요. 7일 동안 보관해요.`,
      `- 실행 로그: [GitHub Actions 실행](${runUrl})`,
    );
  }
  lines.push(
    "- 로컬에서 보기(`frontend`에서):",
    "  - `pnpm test:e2e:ui`: 브라우저 화면을 보며 테스트를 한 단계씩 돌려요.",
    `  - \`pnpm test:e2e -g "${exampleId}"\`: 그 QA ID를 붙인 E2E만 돌려요.`,
    "  - `pnpm test:e2e:report`: 로컬에서 마지막으로 돌린 보고서를 열어요.",
  );

  return `${lines.join("\n")}\n`;
};

const readListReport = () => {
  if (process.env.E2E_LIST_FILE) {
    return JSON.parse(readFileSync(process.env.E2E_LIST_FILE, "utf8"));
  }

  const args = ["exec", "playwright", "test", "--list", "--reporter=json"];
  if (process.env.PLAYWRIGHT_CONFIG) args.push("-c", process.env.PLAYWRIGHT_CONFIG);
  const result = spawnSync("pnpm", args, {
    cwd: FRONTEND_DIR,
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, PLAYWRIGHT_JSON_OUTPUT_FILE: "" },
  });
  if (result.status !== 0) {
    throw new Error(`E2E 목록을 읽지 못했어요.\n${result.stderr || result.stdout}`);
  }
  return JSON.parse(result.stdout);
};

const writeOutput = (key, value) => {
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
};

const detect = (prBody) => {
  const { items, errors } = parseRelatedQa(prBody);
  const related = items.length > 0 || errors.length > 0;
  writeOutput("related", String(related));
  console.log(`related=${related}`);
};

const report = (prBody) => {
  const file = process.env.E2E_RESULT_FILE;
  let resultReport = null;
  if (file && existsSync(file)) {
    try {
      resultReport = JSON.parse(readFileSync(file, "utf8"));
    } catch (error) {
      console.error(`E2E 결과 파일을 읽지 못했어요: ${error.message}`);
    }
  }

  const markdown = renderReport({
    prBody,
    resultReport,
    run: {
      serverUrl: process.env.GITHUB_SERVER_URL,
      repository: process.env.GITHUB_REPOSITORY,
      runId: process.env.GITHUB_RUN_ID,
      artifactUrl: process.env.ARTIFACT_URL || undefined,
      sha: process.env.HEAD_SHA,
    },
  });

  if (process.env.QA_REPORT_FILE) writeFileSync(process.env.QA_REPORT_FILE, markdown);
  else process.stdout.write(markdown);
};

const check = (prBody) => {
  let listReport;
  try {
    listReport = readListReport();
  } catch (error) {
    console.error(error.message);
    process.exit(2);
  }

  const result = runCheck({ prBody, listReport });
  writeOutput("grep", result.grep);
  console.log(result.text);

  if (!result.ok) {
    // CI에서는 고칠 것을 PR의 Files changed와 실행 화면에도 보이도록 주석(annotation)으로 한 번 더 낸다
    if (process.env.GITHUB_ACTIONS) {
      for (const error of result.errors) console.error(`::error title=qa-link::${error}`);
    }
    process.exit(1);
  }
};

const main = () => {
  const prBody = process.env.PR_BODY ?? "";
  if (process.argv.includes("--detect")) return detect(prBody);
  if (process.argv.includes("--report")) return report(prBody);
  return check(prBody);
};

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
