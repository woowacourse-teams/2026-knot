---
name: knot-pr
description: “PR 본문 작성해줘”, “PR 준비 상태 확인해줘”, “PR 생성해줘”, “PR 올려줘”처럼 Knot 백엔드의 실제 git diff와 연결된 GitHub Issue로 PR을 작성·검증·게시할 때 사용한다. 기본 단위는 HTTP API 하나이며 변경량은 약 1,000줄을 목표로 한다. 초안 요청은 게시 권한이 아니며 실제 게시도 명시적 요청에만 수행한다. commit, push, Issue 수정, merge에는 사용하지 않는다.
---

# Knot PR

Use this skill from the repository's `backend` module directory for backend PR drafting and review. Do not rely on a user-specific absolute path. If the skill is invoked from another directory, resolve the checkout dynamically:

~~~bash
repo_root="$(git rev-parse --show-toplevel)"
backend_dir="$repo_root/backend"
cd "$backend_dir"
~~~

The PR is based on two sources:

1. the actual backend change shown by `git diff`;
2. the GitHub Issue linked from the branch.

The diff is the source of truth for what changed. The Issue is the source of truth for why it changed and what must be checked. Do not describe Issue TODOs as completed work unless the diff and verification support them.

## Operating rules

- Work from the `backend` directory. Resolve the Git repository root with `git rev-parse --show-toplevel` when reading root-level rules.
- Treat the repository's `CONTRIBUTING.md`, `.github/knot-conventions.yml`, and `.github/pull_request_template.md` as project sources of truth. User instructions override them.
- Do not commit, push, create a PR, edit an Issue, add labels, or close an Issue unless the user explicitly requests that exact write action.
- Separate drafting from publishing. `PR 생성해줘` and `PR 올려줘` explicitly authorize PR publication; resolve and verify the final title, base, head, Issue linkage, and body from repository evidence before publishing. Drafting, body-writing, and readiness-review requests do not authorize publication. In the `knot-feature` flow (root `AGENTS.md`, 「기능 구현 요청 파이프라인」), the completion condition 「개발까지」 explicitly authorizes commit, push, and PR publication, and the first body section is `## 노션 변경 사항`. In that flow, take the assignee, name label, and reviewers from `python3 harness/team_roles.py review --area backend --author <implementer>` instead of copying them from the Issue, and never merge.
- Keep each backend API PR to one HTTP method-and-path endpoint. Include the endpoint's directly required implementation, tests, schema changes, and internal support code, but do not bundle a second independently callable endpoint.
- Treat roughly 1,000 changed lines as a target, not a minimum or hard cap. Preserve a coherent single-endpoint change when its necessary implementation is larger, and explain the size in `참고 사항`; never pad a smaller endpoint change to meet the target. Endpoint isolation takes priority over line count.
- The 999/1,000-line reference excludes test code. Report the implementation-line count without test sources and the full diff count separately when both help reviewers understand the PR size. Tests remain required when the Issue calls for them.
- If the Issue or diff spans multiple endpoints, identify the method and path for each and mark readiness as `보완 필요`. Recommend endpoint-focused Issue/PR boundaries when repository evidence supports them, but do not create or edit Issues or split branches on the user's behalf.
- Do not claim tests, formatting, review, or Issue completion unless a command or GitHub state proves it.
- Flag unrelated commits, files, generated artifacts, pre-existing dirty changes, and branch-rule mismatches instead of hiding them.

## 1. Resolve branch, base, and linked Issue

Run these read-only checks from `backend`:

~~~bash
git status --short --branch
git branch --show-current
git rev-parse --show-toplevel
git log --oneline --decorate -20
git diff --stat -- .
git diff --name-status -- .
git diff --cached --stat -- .
git diff --cached --name-status -- .
git diff HEAD --stat -- .
git diff HEAD --name-status -- .
~~~

Resolve the base branch from an existing PR when available; otherwise use the repository default branch or configured upstream. Do not assume `main`.

Parse the current branch using the repository convention:

~~~text
<area>/<type>/#<issue-number>
~~~

For the current Knot rules:

- area: `be` or `fe`;
- type: `feature`, `bugfix`, `chore`, `docs`, `hotfix`, `refactor`, or `release`;
- issue-number: the numeric Issue identifier after `#`.

Quote branch names containing `#` in shell commands. If the branch does not match the configured pattern, report the mismatch; do not silently rewrite the branch or title.

Fetch the Issue identified by the branch using the GitHub connector first. Read its title, state, labels, assignee, parent, sub-issues, and sub-issue summary. Use a narrow GitHub CLI/API fallback only when the connector cannot provide a field.

Report the hierarchy separately from the PR body:

~~~text
상위 이슈: #... or 없음
현재 이슈: #...
하위 이슈: #... — 제목 — 상태 or 없음
~~~

Do not infer that every child or sibling is part of this PR. If the branch Issue is a sub-issue, use the parent for context and keep the branch Issue as the default PR link.

## 2. Build the implementation evidence from the diff

Inspect the complete change represented by the backend, in this order:

~~~bash
git diff -- .
git diff --cached -- .
git diff HEAD -- .
git diff <base>...HEAD -- .
git diff --check <base>...HEAD -- .
git log --oneline <base>..HEAD -- .
git diff --stat <base>...HEAD -- .
git diff --name-status <base>...HEAD -- .
~~~

Use the first three commands to include unstaged, staged, and local-but-committed changes. Use `<base>...HEAD -- .` from the backend module directory to represent the branch's committed backend PR scope relative to the resolved base. If the working tree is dirty, distinguish pending local changes from the committed branch diff.

For untracked files, use `git status --short -- .` and inspect only the listed files relevant to the Issue; plain `git diff` does not include untracked files.

Then map the Issue to the diff before drafting:

~~~text
요구사항 | diff 근거(파일/변경) | 검증 근거 | 상태
~~~

Classify each requirement as `충족`, `부분 충족`, `미충족`, or `검증 안 됨`. The PR 작업 내용 must summarize only changed behavior and structure evidenced by the diff. Missing or unrelated work belongs in 참고 사항 or blockers.

Check the scope explicitly:

- name the primary endpoint as `<HTTP method> <path>` and confirm the PR diff changes only that endpoint's external behavior; a second method on the same path is a second endpoint;
- calculate the committed diff with `git diff --numstat <base>...HEAD -- .`; report additions plus deletions excluding test-source paths for the 999/1,000-line reference, and report the full diff size separately. Report local uncommitted changes separately;
- if the diff is materially above the target, check whether unrelated endpoint work can be separated. If the excess is necessary for this one endpoint, retain the coherent change and explain why; size alone is not a blocker;
- changed files belong to the linked Issue and backend area;
- implementation and direct tests are included when the Issue requires tests;
- setup or generated files are explained;
- the linked Issue provides a focused scope for the primary endpoint; if it covers several endpoints, identify the mismatch and recommend a narrower child Issue when one exists;
- sibling Issue work is not presented as this PR's work;
- commit messages describe one coherent change;
- the branch diff does not accidentally include unrelated commits from the base point.

## 3. Build the PR title from the Issue

Use the linked Issue title as the semantic source, but do not let it override the diff's actual scope.

- Preserve `[BE]` or `[FE]` when it matches the branch area.
- Add `[BE]` for `be` or `[FE]` for `fe` when the Issue title has no area prefix.
- Keep the Issue wording by default; do not invent a different feature name.
- Flag a title/Issue/diff mismatch instead of silently changing the Issue's meaning.
- Ensure the title follows the repository title pattern and its area matches the branch.

Expected form:

~~~text
[BE] Notion 페이지 조회 기능 구현
~~~

## 4. Draft the PR body

Use the repository template's exact headings. Remove placeholder comments from the final draft.

~~~markdown
## 관련 이슈

- #<branch-issue-number>

## 작업 내용

- <git diff로 확인된 변경 동작 또는 구조>
- <주요 변경 파일/범위>

### 참고 사항

- <상위/하위 이슈 관계, 제한 사항, 검증 결과>
~~~

Rules:

- Keep `관련 이슈` and `작업 내용` exactly; the repository validator requires both.
- Put the branch Issue number in `관련 이슈`; it must match the branch number.
- Use `Closes #number` only when the user explicitly wants automatic Issue closure. Otherwise use `- #number`.
- Write `작업 내용` from the diff, not by copying the Issue description.
- For a backend API PR, name its single target as `<HTTP method> <path>` in `작업 내용`.
- Mention parent and sub-issues in `참고 사항` when they clarify scope or when the user asks for hierarchy visibility.
- Include actual verification commands and results.
- When a coherent single-endpoint PR materially exceeds the roughly 1,000 changed-line target, explain why in `참고 사항`.
- State incomplete tests, known formatting failures, unrelated changes, or deferred requirements explicitly.
- Never claim the Issue is complete merely because code exists on the branch.

## 5. Verify readiness

Run the smallest relevant checks and report the actual result:

- governance tests from `backend`: `python3 -m unittest discover ../.github/scripts -p 'test_*.py' -v`;
- Java backend changes: `./gradlew test` and `./gradlew spotlessCheck`;
- existing PR governance check when a PR exists: `python3 ../.github/scripts/validate_governance.py --config ../.github/knot-conventions.yml --repo OWNER/REPO --pr PR_NUMBER`. The script resolves its default config relative to the current directory, so keep `--config` when running from `backend`.

If no PR exists, manually validate the draft against the configured branch pattern, title pattern, exact headings, Issue reference, diff scope, and Issue-to-diff evidence table. A successful test task may still have zero relevant tests; inspect the test scope before claiming behavioral coverage.

Return:

~~~text
PR 준비 상태: 준비됨 / 보완 필요
~~~

List blockers separately from optional polish. Include the exact branch, Issue hierarchy, title, body, changed scope, commits, requirement mapping, and verification status needed for review.

## 6. Publish only on explicit request

When the user explicitly asks to create or publish the PR, resolve and verify the final title, base, head branch, Issue linkage, and body before publishing. Ask only when repository evidence is missing or conflicting in a way that materially changes the PR. Do not close the Issue or add extra Issue references without permission. Use the repository's GitHub publishing workflow for commit, push, and PR creation rather than performing those mutations during a draft-only request.
