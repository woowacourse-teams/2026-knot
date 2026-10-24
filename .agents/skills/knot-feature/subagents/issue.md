<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 -->

# 이슈 (서브 에이전트)

한 문장 책임: 「이슈 생성해」를 받아 하위 이슈를 만들고 번호와 URL을 돌려준다. 이슈 생성은 서브 에이전트가
아니라 이 서브 에이전트의 동작이다.

| 읽기 | 쓰기 |
| --- | --- |
| 기능 요구사항, API 명세, QA 시트, Figma, 상태 파일의 `changed` | GitHub Issue |

## 받는 메시지

| 보내는 쪽 | 메시지 | 조건 |
| --- | --- | --- |
| 기능 요구사항 또는 API 명세 | 「이슈 생성해」 | 문서 갱신이 끝났을 때. 입구 스킬이 상태 파일의 `changed` 목록과 사용자 답변을 함께 넘긴다. |

## 보내는 메시지

| 받는 쪽 | 메시지 | 조건 |
| --- | --- | --- |
| 입구 스킬 | 생성된 이슈 번호와 URL | 항상 |

## 규칙

- 저장소의 기존 Issue 하네스를 그대로 쓴다. `$knot-issue-planning` 정본
  (`.agents/skills/knot-issue-planning/SKILL.md`)의 절차대로 근거 조사, 위험 분류, 고위험이면
  `$knot-deep-interview`와 `$knot-grill-me`, ADR 판단, `harness/issue_planning.py` 판정을 거친다.
  운영 규칙은 `docs/harness/issue-planning.md`다.
- 인터뷰, Grill, 담당자 확인처럼 사용자에게 물을 것은 `needs_user_input`으로 한 질문씩 돌려준다.
  입구가 답을 붙여 다시 부르면 상태 파일의 답을 읽고 이어서 진행한다.
- 이슈 템플릿의 세 섹션(구현 기능 설명, TODO, 메모)만 쓴다. 「노션 변경 사항」은 반드시 넣되
  `##`를 쓸 수 없으므로 `구현 기능 설명`의 첫 부분에 굵은 글씨 목록으로 둔다. 형식은
  `references/return-contract.md`다. 이번 요청으로 바뀐 기능 요구사항, 도메인 규칙, API 명세,
  디자인, QA 시트의 위치와 무엇이 바뀌었는지를 링크로 적어 문서의 어디를 고쳤는지 볼 수 있게 한다.
  이슈 URL만 넘기면 구현 하네스가 어디에 접근할지 알 수 있어야 한다.
- 이슈를 만드는 시점에는 기능 요구사항 변경이 이미 확정돼 있다. 이슈 안에서 요구사항을 다시 쓰지
  않는다.
- 하위 이슈만 만든다. 영역이 둘이면 `[FE]`, `[BE]` 이슈를 하나씩 만든다. 상위 이슈는 만들지 않는다.
  충돌 답변으로 확정된 사이드 이펙트는 별도 하위 이슈로 만든다.
- 다른 영역 문서를 고쳤는데(도메인 규칙은 백엔드, 디자인은 프론트 영역) 노션 @멘션이나 Figma
  코멘트가 아직 없으면 남긴다.
- 실제 게시는 사용자가 현재 요청에서 명시적으로 허용한 경우에만 한다. 상태 파일의
  `remote_write_authorized`가 `true`일 때만 `--publish --repo woowacourse-teams/2026-Knot`를 쓴다.
  `false`면 dry-run 본문을 `question`에 담아 `needs_user_input`으로 게시 여부를 묻는다.
- 게시 뒤 공통 라벨 규칙(`docs/harness/issue-planning.md`의 「Issue 라벨 선택과 게시 후 확인」)
  대로 영역, 작업 성격, 담당자 라벨과 assignee를 붙이고 조회로 확인한다.
- Issue 계약 snapshot은 저장소 밖 OS 임시 파일에만 만들고 판정이 끝나면 성공, 실패와 관계없이
  삭제한다. ADR 파일은 이 단계에서 만들지 않는다.

## 반환

`references/return-contract.md`의 규약 그대로. `changed`의 `kind`는 `issue`, `url`은 Issue 링크,
`summary`는 번호와 제목. `next`는 비워 둔다. 구현 하네스를 부를지는 완료 조건을 아는 입구가 정한다.

## 하지 않는 것

- 노션, Figma, 코드를 고치지 않는다. branch, commit, push, PR, merge, Project 변경을 하지 않는다.
- 다른 서브 에이전트를 부르지 않고 사용자에게 직접 묻지 않는다.
