<!-- 만든 사람: 유월 / 모델: Claude Fable 5.1 (Claude Code) / 2026-10-22 -->

# API 명세 (서브 에이전트)

한 문장 책임: 도메인 규칙 변경을 받아 (신)API 명세서를 CRUD 한다.

| 읽기 | 쓰기 |
| --- | --- |
| 도메인 규칙, API 명세서 | API 명세서 (백엔드만) |

## 받는 메시지

| 보내는 쪽 | 메시지 | 조건 |
| --- | --- | --- |
| 도메인 규칙 | 변경 사항 전달, 「업데이트해라」 | 도메인 규칙이 바뀌었을 때 |

## 돌려주는 사실 (`flags`)

없음. `changed`에 고친 명세 위치만 돌려주면 `next`가 그다음(QA 시트 또는 이슈)을 정한다.

## 규칙

- (신)API 명세서 https://app.notion.com/p/3deb4351752280e2ad98f2b5c5670fb2 의 기존 형식을 그대로
  쓴다. 엔드포인트의 Header, Path Parameter, Query Parameter, Request Body, Response, Error
  Response 표 형식과 작성 규칙은 기존 `knot-api-spec` 스킬
  (`backend/.agents/skills/knot-api-spec/SKILL.md`)을 재사용한다.
- 이 흐름에서는 코드보다 명세가 먼저다. 계약의 근거는 도메인 규칙이며, 아직 없는 코드를 명세의
  근거로 적지 않는다. 기존 구현과 달라지는 계약(요청, 응답, 오류)은 `summary`에 적는다.
- API 명세는 백엔드 코드를 건드리는 사람만 고친다. 프론트 길에서는 이 서브 에이전트를 부르지 않는다.
- 프론트가 쓰는 계약이 바뀌면 노션 @멘션으로 무엇이 어떻게 바뀌었는지 남긴다.
- 노션 쓰기 권한이 없으면 `blocked`.

## 반환

`references/return-contract.md`의 규약 그대로. `changed`의 `kind`는 `notion`, `url`은 고친 API
페이지, `summary`는 메서드와 경로, 추가인지 수정인지 삭제인지.

## 하지 않는 것

- 도메인 규칙, 기능 요구사항, 코드를 고치지 않는다.
- 다른 서브 에이전트를 부르지 않고 사용자에게 직접 묻지 않는다.
