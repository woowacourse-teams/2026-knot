# Issue 계약 snapshot

`harness/issue_planning.py`에 전달할 최소 JSON 구조다. snapshot 최상위는 객체여야 한다.
문자열은 공백이 아닌 값을, 목록은 공백이 아닌 문자열만 사용한다. 사용자 입력에는
Issue의 세 섹션을 깨뜨리는 Markdown H2(`## `)를 넣지 않는다.

```json
{
  "operation": "draft 또는 create",
  "title": "[BE] 작업 제목",
  "purpose": "해결할 문제와 결과",
  "scope": ["포함 범위"],
  "acceptance_criteria": ["관찰 가능한 완료 조건"],
  "verification": ["검증 방법"],
  "evidence": ["관련 코드·문서·Issue"],
  "risk_signals": ["security"],
  "non_goals": ["제외 범위"],
  "normal_flows": ["정상 흐름"],
  "failure_flows": ["실패·취소·재시도 흐름"],
  "recovery_flows": ["복구 흐름"],
  "impacts": ["FE·BE·데이터·외부 영향"],
  "dependencies": [],
  "residual_risks": [],
  "interview": {
    "status": "skipped",
    "evidence": {
      "context": {"summary": "현재 맥락", "sources": ["문서 또는 대화 출처"]},
      "situation": {"summary": "구체적인 문제 상황", "sources": ["문서 또는 대화 출처"]},
      "need": {"summary": "선택 필요성", "sources": ["문서 또는 대화 출처"]},
      "alternatives": {"summary": "실제 대안", "sources": ["문서 또는 대화 출처"]},
      "decision": {"summary": "최종 선택", "sources": ["문서 또는 대화 출처"]},
      "rationale": {"summary": "선택 이유", "sources": ["문서 또는 대화 출처"]}
    },
    "conflicts": [],
    "current_validity": "confirmed",
    "resolved_questions": []
  },
  "grill": {
    "status": "pass",
    "resolved_questions": ["해결한 차단 질문"]
  },
  "adr": {
    "required": true,
    "status": "proposed",
    "decision": "한 줄 결정",
    "context": "논의가 시작된 배경과 제약",
    "situation": "누가 언제 어떤 문제를 겪었는지 보여주는 구체적 상황",
    "decision_drivers": ["판단 기준"],
    "alternatives": ["대안과 트레이드오프"],
    "alternatives_confirmed": true,
    "long_term_impact": true,
    "future_reference": true,
    "rationale": "채택 이유",
    "consequences": ["긍정·부정 결과"],
    "revisit_when": ["재논의 조건"],
    "slug": "auth-policy",
    "decision_makers": "Knot 팀"
  }
}
```

고위험 계약은 `interview.status`가 `completed` 또는 `skipped`여야 한다. `skipped`는 여섯
evidence의 요약과 출처가 모두 있고, `conflicts`가 비어 있으며,
`current_validity=confirmed`일 때만 사용한다. 결과의 `interview_notice`를 사용자에게
그대로 보여준다.

자료가 누락·충돌했거나 현재 유효성이 불명확해 사용자에게 질문했다면 `completed`를
사용하고 `resolved_questions`에 해소한 질문과 결론을 요약한다. 인터뷰 여부와 관계없이
최종 evidence는 모두 채운다. Lightweight 계약에는 `interview`가 필요 없다.

ADR이 필요하지 않으면 다음 구조를 사용한다.

```json
{
  "required": false,
  "reason": "장기 결정이 아닌 이유"
}
```

`required=true`는 실제로 논의한 대안이 둘 이상이고, `long_term_impact`와
`future_reference`가 모두 `true`일 때만 사용한다. `alternatives_confirmed=true`는 사용자가
대안 목록이 실제 논의 내용이라고 확인했다는 뜻이다. AI가 제안한 선택지를 이 목록에
섞지 않는다. `slug`는 소문자 영문, 숫자와 하이픈만 사용한다. `decision_makers`는 ADR
파일에 표시할 선택 필드이며 생략하면 `Knot 팀`을 사용한다.

새 Issue를 기획할 때는 아직 번호를 알 수 없으므로 snapshot에 실제 ADR 경로를 만들지
않는다. Issue 본문에는 `backend/docs/adr/{ISSUE_NUMBER}-<slug>.md`를 표시하고, Issue가 생성된 뒤
materializer에 `--issue-number <실제 번호>`를 전달해 경로를 확정한다. 기존 Issue를
다루거나 snapshot을 복구할 때는 최상위 `issue_number`를 양의 정수로 넣을 수 있다.
선택 필드 `adr.planned_path`를 함께 넣으면 번호와 slug가 모두 일치해야 한다.

snapshot은 저장소에 저장하지 않는다. OS 임시 파일을 현재 사용자만 읽을 수 있게 만들고
판정 또는 ADR 생성이 끝나면 성공·실패와 관계없이 삭제한다. 인터뷰 원문, 토큰,
비밀번호와 개인정보를 넣지 않는다.

기본 dry-run 결과 필드는 다음 의미를 가진다.

- `action`: 실제 수행한 동작이다. 기본 통과 계약은 `render_draft`, 새 게시 성공은
  `publish_issue`, 계약 표식으로 기존 Issue를 재사용하면 `reuse_existing_issue`, 생성 뒤
  후속 동기화가 실패하면 `partial_publish_issue`다.
- `requested_action`: `operation=create`이면 `publish_issue`, 초안이면 `render_draft`다.
- `publish_ready`: 생성 의도로 들어온 Issue 후보 계약이 통과했는지 나타낸다. ADR 실제
  경로가 확정됐다는 뜻은 아니다.
- `remote_write_authorized`: 기본값은 `false`다. 사용자가 현재 요청에서 실제 GitHub Issue
  생성을 명시적으로 허용했고 CLI를 `--publish --repo OWNER/REPO`로 실행해 계약이 통과한
  경우 `true`다. 승인된 검색·생성·갱신의 성공 여부와 권한은 다르므로 `status`와 `action`을
  함께 확인한다.
- `issue_url`, `issue_number`: 새로 만들거나 계약 표식으로 재사용한 실제 GitHub Issue다.
- `interview_status`: 고위험 계약의 인터뷰가 `completed` 또는 `skipped`인지 나타낸다.
- `interview_notice`: 사용자에게 그대로 보여줄 인터뷰 완료 또는 생략 안내다.
- `adr_path_status`: 실제 Issue 번호 전이면 `pending_issue_number`, 게시기가 같은 Issue
  본문을 실제 번호로 갱신한 뒤에는 `finalized`다.
- `next_after_issue_created`: 번호 확정이 필요하면 `finalize_adr_path`다.

`operation=create`, `requested_action=publish_issue`, `publish_ready=true`만으로는 원격 쓰기
권한이 아니다. 게시 전 같은 계약 표식을 모든 상태의 Issue에서 검색하며, 하나면 재사용하고
둘 이상이면 `hold`한다. Project 변경은 이 권한에 포함되지 않는다.
