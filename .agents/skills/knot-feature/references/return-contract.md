<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 / 2026-10-23 next를 flags로 바꿈 -->

# 반환 규약과 상태 파일

모든 서브 에이전트는 마지막 메시지로 아래 JSON 하나만 돌려준다. 입구 스킬은 이 값만 보고 다음 행동을
정한다. 설명이 필요하면 JSON 안의 문자열에 넣고 JSON 밖에 글을 쓰지 않는다.

서브 에이전트는 다음에 누구를 부를지 정하지 않는다. 자기가 알아낸 사실을 `flags`에 돌려주고,
다음 단계는 `python3 harness/feature_pipeline.py next`가 상태 파일만 보고 정한다. 분기가 AI 판단이
아니라 테스트된 코드에 있어야 흐름이 매번 같기 때문이다(2026-10-23 팀 피드백).

```json
{
  "status": "done | needs_user_input | blocked",
  "changed": [{"kind": "notion | figma | issue | pr", "url": "...", "summary": "무엇이 어떻게 바뀌었나"}],
  "flags": {"requirements_changed": true, "backend_rules_affected": false, "screen_changed": true, "figma_url": "https://..."},
  "question": "needs_user_input일 때만. 선택지와 권장안 포함",
  "side_effects": ["충돌이나 영향 범위가 있을 때"]
}
```

| 필드 | 뜻 |
| --- | --- |
| `status` | `done`은 이 서브 에이전트의 일이 끝났다. `needs_user_input`은 사람의 답이 있어야 이어갈 수 있다. `blocked`는 권한, 접근 실패, 정본 부재처럼 사람이 환경을 손봐야 한다. 추측으로 우회하지 않는다. |
| `changed` | 이 서브 에이전트가 실제로 바꾼 것. 그대로 이슈와 PR의 「노션 변경 사항」이 된다. `url`은 고친 위치(페이지, DB 행, Figma 노드, Issue, PR)를 바로 열 수 있는 링크다. 바꾼 것이 없으면 빈 배열이다. 구현이 참고할 링크만 돌려줄 때는 `summary`에 「변경 없음, 참고 링크」라고 적는다. |
| `flags` | 이 서브 에이전트가 알아낸 사실. 아래 표의 키만 쓰고, 모르는 키는 넣지 않는다. 돌려줄 사실이 없으면 생략한다. `next`는 더 이상 받지 않는다(검증기가 거부한다). |
| `question` | `needs_user_input`일 때만. 무엇을 왜 묻는지, 고를 수 있는 선택지, 권장안을 한 덩어리로 쓴다. `blocked`일 때는 사람이 손볼 것을 적는다. |
| `side_effects` | 충돌이나 영향 범위. 기능 요구사항 충돌 브리핑은 여기에 담긴다. |

## `flags`: 누가 어떤 사실을 돌려주나

| 키 | 형 | 돌려주는 서브 에이전트 | 뜻 |
| --- | --- | --- | --- |
| `requirements_changed` | bool | 기능 요구사항 | 기능 요구사항 DB에 추가나 수정이 있었다. `true`면 QA 시트를 부른다. |
| `backend_rules_affected` | bool | 기능 요구사항 | 요구사항 문장에 백엔드 규칙이 바뀌는 내용(검증, 상태, 권한, 저장, 계산)이 있다. 판단이 서지 않으면 `true`. 백엔드 길에서만 도메인 규칙을 부른다. |
| `domain_rules_changed` | bool | 도메인 규칙 | 기존 규칙과 비교해 실제로 바뀐 것이 있다. `true`면 API 명세를 부른다. |
| `screen_changed` | bool | 기능 요구사항 | 사용자가 보는 화면이 바뀐다. |
| `figma_url` | URL 또는 null | 기능 요구사항, 디자인 | 구현이 참고할 Figma 링크. 프론트 길에서 `screen_changed`인데 이 값이 없으면 디자인을 부른다. |

분기 규칙 전체는 `harness/feature_pipeline.py`의 `next_stage`와 입구 스킬 3절의 표가 같은 내용이다.
둘이 다르면 코드가 정본이고 문서를 고친다.

## 검증기와 분기기

입구는 서브 에이전트가 돌려준 JSON과 상태 파일을 저장소의 결정적 스크립트로 확인하고, 다음 단계를
스크립트가 정하게 하며, `changed`를 「노션 변경 사항」 Markdown으로 만든다. 판단 규칙은 이 문서와
`next_stage`가 정본이고 스크립트는 그것을 기계적으로 확인할 뿐이다.

```bash
python3 harness/feature_pipeline.py result <result.json> --pretty   # 반환값 검증
python3 harness/feature_pipeline.py state <state.json> --pretty     # 상태 파일 검증
python3 harness/feature_pipeline.py next <state.json> --pretty      # 다음에 부를 서브 에이전트와 메시지
python3 harness/feature_pipeline.py status <state.json>             # 어디까지 했고 다음이 무엇인지 요약
python3 harness/feature_pipeline.py render <state.json>             # PR용 ## 노션 변경 사항
python3 harness/feature_pipeline.py render <state.json> --issue     # 이슈용 (## 없이 굵은 제목)
```

`next`의 결과는 `status`가 `next`(부를 `agent`와 `message`, 그 `reason`), `waiting`(답을 기다리는
`question`), `blocked`, `finished` 중 하나다. 입구는 `next`가 가리키는 서브 에이전트만 부른다.
`status`는 작업을 끊고 돌아왔을 때 먼저 실행해 「방금 뭐 하고 있었지」에 답한다.

검증에 실패한 반환값은 상태 파일에 넣지 않고, 같은 서브 에이전트를 규약을 지키라는 말과 함께 한 번
다시 부른다. 두 번째도 실패하면 `blocked`로 보고 멈춘다.

## 「노션 변경 사항」 형식

`changed` 배열을 아래 형식으로 옮긴다. 내용을 베끼지 않고 링크만 단다. 바뀐 문서가 없는 항목은
「없음」과 이유를 적는다.

```markdown
## 노션 변경 사항

- 노션: [기능 요구사항 SEARCH-R12 추가. 복사 버튼을 누르면 알림이 뜬다](https://app.notion.com/p/...)
- 노션: [QA 시트 SEARCH-QA-031 추가](https://app.notion.com/p/...)
- Figma: [탐색 답변 복사 화면, 변경 없음, 참고 링크](https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=...)
```

`summary`는 어느 문서의 무엇이 바뀌었는지로 시작한다(기능 요구사항, 도메인 규칙, API 명세, QA 시트,
디자인). 백엔드 길이 아니어서 고치지 않은 문서는 적지 않는다. 바뀐 것이 하나도 없으면 `- 없음`이다.

- PR 본문에서는 첫 번째 `##` 섹션으로 둔다. 그 아래는 기존 PR 템플릿을 따른다.
- 이슈 본문은 세 섹션(`구현 기능 설명`, `TODO`, `메모`)만 허용하므로 `##`를 쓰지 않고
  `구현 기능 설명`의 첫 부분에 굵은 글씨 「노션 변경 사항」과 같은 목록을 둔다.

## 상태 파일

경로는 `${TMPDIR:-/tmp}/knot-feature/<기능-slug>/state.json`이다. 디렉터리 `700`, 파일 `600`.
저장소 밖이며 커밋하지 않는다. 문서 본문, 토큰, 비밀번호, 개인정보를 넣지 않는다.

```json
{
  "request": "요청 원문",
  "existing_issue_url": null,
  "kind": "feature | fix | chore",
  "area": "frontend | backend | both",
  "done_condition": "issue | development",
  "remote_write_authorized": false,
  "implementation_order": ["backend", "frontend"],
  "answers": [{"to": "기능 요구사항", "question": "...", "answer": "..."}],
  "assumptions": [{"stage": "기능 요구사항", "assumed": "탐색 도메인 아래에 추가", "reason": "요청이 탐색 화면을 가리킨다"}],
  "steps": [
    {"agent": "기능 요구사항", "message": "기존과 대조해 업데이트해라", "result": {"status": "done", "changed": [], "flags": {}, "question": "", "side_effects": []}}
  ],
  "issues": [{"area": "frontend", "number": 0, "url": "..."}],
  "prs": [{"area": "frontend", "url": "..."}]
}
```

| 필드 | 뜻 |
| --- | --- |
| `kind` | 요청 분류. `feature`는 사용자가 보는 동작이 바뀌는 요청이고 문서 단계를 거친다. `fix`(깨진 것을 고침)와 `chore`(코드 내부만)는 문서 단계를 건너뛰고 이슈부터 간다. 기준은 입구 스킬 1절. |
| `implementation_order` | 영역이 `both`일 때 구현 순서. 기본은 백엔드 먼저다. 프론트가 선행해야 하면 입구가 바꾼다. 생략하면 기본값. |
| `answers` | 사용자가 준 답. `to`와 `question`이 마지막 단계의 `needs_user_input`과 같으면 `next`가 같은 서브 에이전트를 다시 부른다. |
| `assumptions` | 사람에게 묻지 않고 기본값으로 처리한 미결. 끝내기 보고와 PR 본문의 「기본값으로 처리한 미결 사항」이 된다. 기준은 입구 스킬 4절. |
| `remote_write_authorized` | 사용자가 현재 요청에서 GitHub Issue 생성을 명시적으로 허용했을 때만 `true`. 이슈 서브 에이전트는 이 값이 `true`일 때만 `--publish`를 쓴다. |
