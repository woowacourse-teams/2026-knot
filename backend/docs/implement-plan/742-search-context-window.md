# #742 탐색 반복 문맥 축소 실험

상태: 코드 구현 및 로컬 검증 완료, Dev B 측정 대기
확인일: 2026-10-17
대상: [#742 탐색 LLM 개선](https://github.com/woowacourse-teams/2026-knot/issues/742)
브랜치: be/feature/#742

## 1. 범위와 브랜치

이번 B 실험은 탐색 LLM 요청에 이전 도구 호출 결과가 계속 누적되는 동작을 바꿈. 매 요청에는 시스템 지침, 기존 대화 문맥, 사용자 질문, 가장 최근 도구 호출 묶음만 전달함. 이전 탐색 결과는 다음 요청에서 제외하되, SearchExploration이 수집한 전체 읽기 문서는 최종 답변 생성기에 계속 전달함.

변수는 탐색 요청 문맥 보존 방식 하나로 제한함. 도구 종류, 검색 입력, 도구 호출 한도, 최종 답변 프롬프트, 모델 설정, 질문과 데이터는 바꾸지 않음. 같은 질문 30개 B 측정에서 첫 delta, 완료 시간, 종료 상태, 답변 정확성, 근거 일치율을 A와 비교함.

기존 be/feature/#742 worktree와 브랜치를 재사용함. 기준 브랜치는 origin/develop의 7f32d31b0임. 별도 브랜치는 만들지 않음. worktree에 이미 있는 backend/docs/qa/742-search-a-baseline.md는 기존 변경으로 보존함. 이 문서는 그 기준선 파일을 수정하지 않음.

| 근거 | 확인 시점 | 이번 작업에 적용하는 내용 | 판정 |
| --- | --- | --- | --- |
| [GitHub Issue #742](https://github.com/woowacourse-teams/2026-knot/issues/742) | 2026-10-17 | 첫 개선은 속도이며, 같은 30개 질문으로 한 번에 한 변경을 검증함. | 사용자 작업 계약 |
| [Notion 02. 속도 개선 실험](https://app.notion.com/p/3fc0691a002381899022e9a2f7e320d7) | 2026-10-17 조회 | A 첫 delta 중앙값 25.57초, 잠정 P95 44.39초, 첫 delta 전 실패 5건. 탐색 LLM 호출은 104회, 요청당 평균 3.47회임. 동일 조건 B 결과는 아직 없음. | 현재 실험 기준선 |
| backend/docs/qa/742-search-a-baseline.md | 2026-10-17 | Q01-Q30, 기대 사실과 근거, 질문별 A 시간 및 종료 상태를 고정함. | 기존 측정 기록 |
| SearchMcpDocumentRetriever.retrieve | 현재 코드 확인 | messages 목록에 매 도구 호출 응답을 누적하고 다음 LLM 요청에도 전체 목록을 전달함. | 구현 관측 |
| SearchAgentContext.validate | 현재 코드 확인 | 직렬화한 탐색 messages의 문자 수를 제한함. | 구현 관측 |
| SearchExploration 및 SearchStreamingAnswerGenerator | 현재 코드 확인 | 읽은 전체 문서는 별도 누적되어 최종 답변 생성기에 전달됨. | 구현 관측 |
| 백엔드 .persona 지침 | 주 checkout에서 읽기 전용 확인 | Java 25, Spring Boot, Gradle, JUnit과 Mockito 사용. 현재 worktree에는 로컬 .persona 디렉터리가 없어 Persona workflow 명령은 비활성 상태임. | 프로젝트 설정 관측 |

## 2. 구현할 흐름

사용자는 이전과 같이 질문을 전송함. 서버는 같은 인증, 의도 분류, Workspace 권한 확인과 생성 작업 흐름을 거침. 이번 변경은 문서 탐색 실행기의 다음 LLM 요청에 전달하는 messages 범위만 바꿈. 읽은 문서의 전체 본문과 evidence 저장 흐름은 유지함.

~~~mermaid
sequenceDiagram
    actor User as 사용자
    participant API as Search API
    participant Worker as Search 작업 실행기
    participant Retriever as SearchMcpDocumentRetriever
    participant LLM as 탐색 LLM
    participant MCP as 문서 도구
    participant Answer as 최종 답변 생성기
    participant DB as DB 및 SSE

    User->>API: 질문 전송
    API->>DB: 질문과 답변 메시지 저장
    API-->>User: accepted
    API->>Worker: 답변 생성 작업 실행
    Worker->>Retriever: 질문과 검색 범위 전달
    loop 추가 근거 탐색이 필요한 동안
        Retriever->>LLM: 기본 문맥과 가장 최근 도구 호출 묶음 전달
        LLM-->>Retriever: 도구 호출 또는 탐색 완료
        opt 도구 호출이 있음
            Retriever->>MCP: 현재 Workspace 범위로 도구 실행
            MCP-->>Retriever: 도구 결과 반환
            Retriever->>Retriever: 전체 읽기 문서 누적, 다음 요청 문맥을 최신 묶음으로 교체
        end
    end
    Retriever-->>Worker: 전체 읽기 문서 반환
    Worker->>Answer: 질문, 대화 문맥, 전체 읽기 문서 전달
    Answer-->>DB: delta와 종료 상태 저장 및 SSE 전달
    DB-->>User: delta, completed 또는 실패 이벤트
~~~

첫 검색 결과는 다음 LLM 요청에서 읽을 문서를 고르는 데 사용함. 다음 도구 호출 묶음을 처리한 뒤에는 더 이전 도구 결과를 다음 요청에서 제외함. 탐색을 마치면 SearchExploration에 누적된 전체 읽기 문서를 답변 생성기에 넘김.

## 3. 나올 코드와 메서드 초안

현재 SearchMcpDocumentRetriever.retrieve가 시스템 지침, 대화 이력, 사용자 질문을 만들고 LLM 및 MCP 호출을 반복함. recordCall은 각 도구 응답을 탐색 메시지에 추가하고 SearchExploration에 결과를 기록함. SearchAgentContext는 각 반복 전에 messages 크기를 검사함.

| 위치 및 클래스 | 책임 | 변경 후보 |
| --- | --- | --- |
| backend/src/main/java/com/knot/backend/search/infrastructure/SearchMcpDocumentRetriever.java | 도구 호출을 반복하고 전체 읽기 문서를 수집함. | retrieve에서 기본 문맥을 별도 보존함. 새 도구 호출 응답을 처리할 때 기본 문맥과 해당 assistant 응답으로 messages를 재구성하고, recordCall이 그 응답의 모든 도구 결과를 추가함. |
| backend/src/test/java/com/knot/backend/search/infrastructure/SearchMcpDocumentRetrieverTest.java | 탐색 도구 호출과 예외 경계를 검증함. | 이전 검색 및 목록 결과가 다음 요청에서 빠지고 최신 도구 결과 묶음, 원 질문, 대화 이력은 남는지 검증함. 최종 반환 문서 본문 전체가 유지되는지도 검증함. |
| SearchAgentContext 및 SearchExploration | 직렬화 문맥 크기 제한과 읽은 문서 수집을 담당함. | 별도 변경 없이 기존 동작을 재사용함. |
| SearchStreamingAnswerGenerator | 최종 답변 요청에 검색 결과를 제공함. | 변경하지 않음. 전체 읽기 문서가 넘어가는 기존 동작을 회귀 검증함. |

## 4. 저장 기반과 주의할 점

DB 스키마, 대화 메시지, 요청 멱등성, evidence 저장 방식은 바꾸지 않음. Flyway migration은 필요하지 않음. 문맥 축소는 탐색 LLM의 다음 요청에만 적용하고 서버 내부 SearchExploration의 읽은 문서 누적에는 적용하지 않음.

주요 위험은 모델이 이전 도구 응답 내용을 다시 참조할 수 없다는 점임. 원 질문과 대화 이력은 매 요청 유지하고 가장 최근 도구 응답 묶음도 보존함. 전체 읽기 문서는 최종 답변 생성기에 전달함. 이전 결과를 잃어버려 검색 정확성이나 답변 품질이 나빠지면 B 결과에서 확인하고 이 변경을 채택하지 않음.

한 번의 도구 응답 묶음 자체가 문맥 제한을 넘는 경우와 이전 대화 이력 자체가 큰 경우는 이번 변경으로 해결되지 않을 수 있음. 이 한계는 B 결과에 실패 원인을 함께 기록함.

## 5. TDD와 검증 순서

| 순서 | 실패하는 테스트 | 최소 구현 | 기대 결과 |
| --- | --- | --- | --- |
| 1 | SearchMcpDocumentRetrieverTest에서 검색 결과에 큰 구분 문자열을 넣고, 다음 요청의 메시지를 캡처함. 현재 동작에서는 세 번째 LLM 요청에도 첫 검색 결과가 남아 실패해야 함. | 구현은 아직 추가하지 않음. | 테스트가 기존 누적 동작을 재현함. |
| 2 | 같은 테스트에서 검색 결과 뒤 읽기 도구 응답을 제공하고, 다음 요청에 기본 system, history, question 및 최신 읽기 응답이 남는지 확인함. | retrieve가 기본 문맥을 보존하고 새 assistant 도구 호출 응답을 처리할 때 기본 문맥과 해당 응답으로 messages를 다시 구성함. | 이전 큰 검색 결과는 빠지고 최신 도구 결과는 유지됨. |
| 3 | 한 assistant 응답에 검색과 목록 도구 호출을 함께 넣어 최신 묶음의 모든 결과가 다음 요청에 남는지 검증함. 반환 결과에서 읽은 본문 전체가 유지되는지도 검증함. | 기존 SearchExploration 집계는 유지하고 recordCall이 현재 assistant 응답에 대한 각 결과를 추가하게 둠. | 최종 답변 입력 근거를 줄이지 않고 이후 탐색 요청만 축소함. |
| 4 | backend 모듈의 SearchMcpDocumentRetrieverTest 및 관련 SearchStreamingAnswerGeneratorTest 실행. | 실패가 있으면 이번 변경 범위 안에서 수정함. | 기존 도구 오류, 호출 한도, 문서 본문 수집과 최종 전달 규칙이 유지됨. |
| 5 | Dev API에서 예열 요청 1회 후 Q01-Q30을 새 대화로 순차 전송함. | 코드 단위 변경 외에 모델, 서버 설정, 질문 및 문서 범위를 바꾸지 않음. | 첫 delta와 완료 시간, 종료 상태, 답변 정합성, evidence 일치율을 A와 비교함. |

Dev B 측정은 동일한 Workspace 31, 목 문서 100건, 질문 Q01-Q30, 모델 및 LM Studio 설정을 사용함. 매 질문은 새 대화로 실행하고 순차 처리함. accepted는 첫 답변으로 세지 않음. FAILED, timeout, 연결 오류를 결과에 남김. Search LLM 로그의 operation=complete 별 messageContentChars 및 가능한 promptTokens를 A와 비교해 실제 요청 문맥이 줄었는지 확인함.

## 6. 완료 기준과 다음 작업

코드 완료는 회귀 테스트가 통과하고, 이전 도구 결과가 다음 LLM 요청에서 제외되며, 최근 결과와 전체 읽기 문서가 보존될 때로 판단함.

실험 채택은 Dev B의 첫 delta 중앙값이 A보다 줄고, 근거 문서 일치율과 답변 정확성이 나빠지지 않으며, 실패 및 timeout 비율이 악화하지 않을 때만 판단함. 그렇지 않으면 변경을 채택하지 않고 질문별 실패와 품질 차이를 기록함. 30건 결과의 P95는 잠정값으로 표시함.

현재 외부 Dev API의 B 실행 및 배포는 아직 확인되지 않음. 실제 Dev 측정 전까지 성능 개선 효과는 미확정으로 유지함. B 실험 뒤에는 #742의 남은 안정성 실패와 검색 정확성 문제를 별도 변수로 다룸.

## 7. 로컬 구현 결과

- `SearchMcpDocumentRetrieverTest` 21개 테스트 통과함. 기존 누적 문맥 동작을 재현하는 테스트가 구현 전에 실패했고, 변경 후 통과함.
- 검색과 목록 도구를 한 번에 호출하는 응답에서도 최신 도구 결과 전체가 다음 요청에 남는지 검증함.
- 다음 탐색 요청에서 원 질문과 대화 이력이 보존되고 이전 검색 및 목록 결과가 제외되는지 검증함.
- 탐색에서 읽은 전체 문서 본문이 최종 검색 결과에 유지되는지 검증함.
- `./gradlew check` 성공함. 통합 테스트, 인수 테스트, Spotless 검사를 포함함.
- Dev B 실행은 새 코드가 배포되기 전이므로 미실행임. 성능 개선 여부는 미확정임.
