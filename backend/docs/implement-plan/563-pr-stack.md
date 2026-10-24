# #563 탐색 구현 PR 분리

## 분리 기준

사용자 요청으로 테스트·문서를 포함한 전체 diff를 PR당 최대 2,000줄로 제한하고 9단계로 나눈다. 원래 커밋을 보존하고 강제 push 없이 기존 #587·#583의 base를 앞 단계 브랜치로 바꾼다. 모든 PR은 Draft이며 하위 이슈 #588–#596을 #563에 연결한다. 마지막 #583에만 `Closes #563`을 사용한다.

| 단계 | 브랜치 | PR base | 책임 |
| --- | --- | --- | --- |
| 1 · #588 | `be/feature/#588` | `be/feature/#565` | 질문 검증·원자적 접수·멱등성 |
| 2 · #589 | `be/feature/#589` | `be/feature/#588` | 생성 작업·복구·SSE 구독 기반 |
| 3 · #590 | `be/refactor/#563` | `be/feature/#589` | HTTP/SSE 계약·global exception handler (#587) |
| 4 · #591 | `be/feature/#591` | `be/refactor/#563` | 문서 권한·목록·커서·근거 저장 구조 |
| 5 · #592 | `be/feature/#592` | `be/feature/#591` | 문서 검색·본문 이어 읽기 |
| 6 · #593 | `be/feature/#593` | `be/feature/#592` | MCP 서버·클라이언트·제한된 HTTP |
| 7 · #594 | `be/feature/#594` | `be/feature/#593` | LLM 도구 호출 HTTP 클라이언트 |
| 8 · #595 | `be/feature/#595` | `be/feature/#594` | 탐색 반복·문맥·실제 읽은 근거 검증 |
| 9 · #596 | `be/feature/#563` | `be/feature/#595` | generator·실제 adapter·설정·전체 검증 (#583) |

기존 PR head는 변경할 수 없으므로 #587·#583은 기존 #563 branch를 유지하고 관련 하위 이슈를 본문에 함께 적는다. 각 PR의 제목·본문은 실제 기능 책임을 설명한다. 후속 #566~#569 endpoint와 운영 배포는 포함하지 않는다.

```mermaid
flowchart LR
    P[PR574] --> A[#588 접수]
    A --> B[#589 작업과 SSE 기반]
    B --> C[#590 HTTP와 SSE - PR587]
    C --> D[#591 권한과 목록]
    D --> E[#592 검색과 읽기]
    E --> F[#593 MCP 통신]
    F --> G[#594 LLM 통신]
    G --> H[#595 탐색 반복]
    H --> I[#596 adapter 연결 - PR583]
    I --> J[최종 병합 시 Closes #563]
```

## 단계별 검증

각 branch HEAD에서 전체 test source 컴파일·`spotlessCheck`·`bootJar` 및 해당 책임의 단위·통합·인수 테스트를 실행한다. 접수 기반과 최종 통합에서는 전체 `check`도 실행한다. 검증 범위를 각 PR 본문에 기록한다. 최종 단계에서 실제 adapter를 연결하며 활성화 설정이 필요하다. 테스트 adapter 검증을 실제 모델 관측으로 표현하지 않는다.

문서 도구 테스트는 4·5단계에서 직접 서비스/DB를 검증하고, 이후 단계에서 실제 MCP HTTP·동시 Workspace 격리·LLM wire를 추가 검증한다. 실제 근거 전체 보존·관련도 순 카드 3개 정책은 저장 구조와 실행 검증 단계에 걸쳐 완성된다. 외부 모델의 두 합성 사례 결과는 기존 QA 문서의 당시 코드 관측이며, 분리 작업에서 공급자를 재호출한 결과가 아니다.

## exception handler 위치

`SearchQuestionExceptionHandler`와 직접 단위 테스트를 `global/exception`으로 옮긴다. SSE 접수 전에는 JSON 오류를 반환하고 응답이 이미 시작되면 JSON을 다시 쓰지 않는 책임은 유지한다. `@RestControllerAdvice(assignableTypes = SearchQuestionController.class)`도 유지하여 다른 Controller의 기존 오류 계약을 바꾸지 않는다.

## 리뷰와 병합

1 → 9 순서로 리뷰한다. 부모 병합 후에는 실제 merge 방식과 고유 diff를 확인해 다음 PR base를 정리한다. 이 작업에서는 PR merge·강제 push를 실행하지 않는다. `git diff --numstat base...head`의 추가·삭제 합계가 2,000줄 이하인지 게시 전에 확인하고 실제 크기를 각 PR 본문에 기록한다.
