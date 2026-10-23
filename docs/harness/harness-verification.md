# 하네스 수정과 검증

하네스를 고치는 작업과 팀 하네스가 실제로 어떻게 도는지 확인하는 방법을 적는다. 2026-10-23 팀
피드백 두 가지에서 나왔다. 하네스를 고치려고 하네스를 타야 하는 번거로움, 그리고 개인 설정이
팀 하네스 검증 결과를 조용히 바꾸는 문제다. 배경은 `improvement-log.md`에 있다.

## 하네스 경로

아래 경로만 바뀌는 작업은 「하네스 수정」이다.

| 경로 | 무엇이 있나 |
| --- | --- |
| `harness/` | 검증기, 분기기, 동기화 스크립트와 테스트 |
| `.agents/skills/`, `.claude/skills/`, `.claude/agents/` | 공통 스킬 정본과 Claude Code 진입점 |
| `docs/harness/` | 운영 규칙과 이 문서 |
| `frontend/.claude/`, `backend/.agents/`, `backend/.claude/` | 영역별 규칙, 스킬, 에이전트 |
| `AGENTS.md`, `CLAUDE.md`, `.mcp.json` | 루트 지침과 MCP 설정 |

## 하네스 수정 경로 (knot-feature와 Issue 계약 면제)

1. 이슈는 `gh issue create`로 바로 만든다. 라벨은 영역(`FE` 또는 `BE`), `Harness`, `Chore`, 담당자
   라벨이고 Issue 계약(`knot-issue-planning`, 위험도, 인터뷰, Grill)은 거치지 않는다. 이슈 템플릿의
   세 섹션은 그대로 쓴다. 브랜치 이름에 이슈 번호가 필요해서 이슈는 만든다.
2. 브랜치는 `fe/chore/#<번호>` 또는 `be/chore/#<번호>`다. 워크트리를 따로 두면 다른 작업과 섞이지 않는다.
3. 게이트는 테스트다. PR을 올리기 전에 반드시 돌린다.

   ```sh
   python3 -m unittest discover harness/tests -v
   ```

   `test_agent_harness_parity.py`가 Claude Code 진입점과 Codex 정본이 같은 곳을 가리키는지,
   `test_feature_pipeline.py`가 반환 규약과 분기 규칙을, `test_issue_planning.py`가 Issue 계약을 본다.
4. PR 본문은 기존 템플릿을 따르고 `작업 내용`에 「빈 프로필로 돌렸음」 여부를 한 줄 적는다(아래 절).
   `## 노션 변경 사항` 섹션은 하네스 수정에 없어도 된다.
5. 규칙 문장을 바꿨으면 그 문장이 어디서 강제되는지(도구 제한, 검증기, CI, 아직 문장뿐)를
   `improvement-log.md`에 적는다.
6. ADR은 기존 결정을 뒤집을 때만 손댄다. 보완이면 해당 ADR에 날짜와 이슈 번호로 한 문단 덧붙인다.

하네스와 제품 코드를 한 PR에서 같이 고치지 않는다. 섞이면 제품 코드 쪽 규칙(Issue 계약, 리뷰)을 따른다.

## 빈 프로필 검증 모드

Claude Code는 `~/.claude/CLAUDE.md`, `~/.claude/skills`, `~/.claude/agents`, `~/.claude/settings.json`의
훅과 권한, 플러그인, 프로젝트별 자동 메모리를 전부 먼저 읽는다. 팀 하네스를 「내 환경에서 잘 돈다」로
확인하면 이것들이 섞인 결과다. 팀 하네스 검증은 개인 설정이 없는 빈 프로필에서 한다.

```sh
# 저장소 루트에서. 빈 디렉터리를 설정 디렉터리로 쓴다.
CLAUDE_CONFIG_DIR="$(mktemp -d)" claude

# headless 위임도 같다.
CLAUDE_CONFIG_DIR="$(mktemp -d)" claude -p "○○ 기능 이슈까지만 만들어 줘"
```

이 모드에서 달라지는 것(Claude Code 문서 `env-vars`, `claude-directory`, `mcp` 기준):

- 사용자 CLAUDE.md, 사용자 스킬과 에이전트, 사용자 훅, 플러그인, 자동 메모리가 로드되지 않는다.
  전부 설정 디렉터리 아래에 있기 때문이다.
- 프로젝트 `.mcp.json`(`knot-notion`, `figma`)은 저장소 루트 파일이라 그대로 로드된다. 다만 승인 기록이
  사용자 설정에 있어서 대화형 세션은 서버 사용을 다시 묻고, `claude -p`는 묻지 않고 로드한다.
- 로그인 상태가 유지되는지는 문서에서 확인하지 못했다. 다시 로그인해야 할 수 있다.
- `CLAUDE_CODE_SIMPLE=1`(`--bare`)은 다른 방법이다. 프로젝트 `.mcp.json` 자동 탐색까지 꺼서 노션과
  Figma가 없어지므로 이 검증에는 맞지 않는다.

Codex는 `CODEX_HOME`으로 같은 일을 한다. `CODEX_HOME="$(mktemp -d)" codex`로 열면 개인
`~/.codex/config.toml`과 스킬이 빠진다. 단 `.mcp.json`은 Codex가 읽지 않으므로 빈 `config.toml`에
`docs/harness/mcp-access.md` 4절의 MCP 설정만 넣는다.

검증한 PR에는 「빈 프로필로 돌렸음」과 어떤 요청으로 돌렸는지를 한 줄 적는다. 돌리지 않았으면
그렇게 적는다. 돌리지 않은 것보다 돌렸다고 잘못 적는 쪽이 나쁘다.

## 팀 하네스가 개인 경로에 기대는 것

팀 하네스 파일이 저장소 밖 경로를 가리키면 그 파일이 없는 팀원에게는 다르게 돈다. 2026-10-23 기준
아래 둘이 남아 있고, 저장소로 옮길지는 유월이 정한다(Codex와 공유하는 원본이라 위치를 바꾸면 개인
설정도 같이 바꿔야 한다).

| 참조하는 곳 | 가리키는 개인 경로 |
| --- | --- |
| `.agents/skills/knot-feature/subagents/design.md`, `references/sources.md` | `~/.local/share/agent-skills/knot-ux-journal/SKILL.md` |
| `references/sources.md` | `~/.local/share/agent-skills/knot-marker-cleanup` |

`harness/tests/test_agent_harness_parity.py`가 팀 하네스 파일에 macOS 홈 디렉터리로 시작하는 절대
개인 경로가 없는지 본다. `~/` 참조는 위 표가 비워지면 같은 테스트로 막는다.
