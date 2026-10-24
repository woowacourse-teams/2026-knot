# 엔티티와 복합키의 필드 애노테이션 점검

[Issue #738](https://github.com/woowacourse-teams/2026-knot/issues/738)의 전수 점검 결과다.
`develop`의 `7f32d31b`를 기준으로 엔티티 22개와 복합키 2개를 확인했다.
수정은 8파일의 빈 줄 9곳 삭제이며 애노테이션과 실행 코드는 그대로다.

## 적용 기준

- 팀 백엔드 컨벤션 03의 생성자 접근 범위·공개 Setter 금지와 05의 enum 문자열 저장 규칙을 확인했다.
- 팀 백엔드 컨벤션 06과 승인 Java 코드 작성 규칙에 따라 첫 필드의 애노테이션은 클래스 선언 다음 줄부터 둔다.
- `@Entity`의 인스턴스 필드는 애노테이션과 선언을 한 블록으로 묶고 블록 사이를 한 줄 띄운다.
- `@Embeddable` 복합키는 현재 일반 클래스 필드 그룹 규칙을 적용해 연속 필드를 붙인다.
- 클래스 애노테이션의 나열 순서는 문서에 강제 규칙이 없어 변경하지 않았다.

원문은 [Entity·DTO·Repository 규칙](https://www.notion.so/3b4b4351752280938936c59f5e0d4610)과
[Java 코드 작성 규칙](https://www.notion.so/3b4b4351752280798e41c6bdc369baf3)의 로컬 export다.
컨벤션 00~08 원문 hash 검사를 통과했으며 팀 Notion은 수정하지 않았다.

## 점검 목록

각 타입에서 클래스·필드 애노테이션, 기본 생성자 접근 범위, 금지 Lombok 사용,
ID·테이블 지정, enum 저장 방식과 애노테이션·필드의 인접 및 공백을 확인했다.

| 도메인 | 타입 | 결과 |
| --- | --- | --- |
| auth | AuthSession | 배치 수정 없음 |
| auth | ConsumedRefreshToken | 배치 수정 없음 |
| auth | OAuthIdentity | 배치 수정 없음 |
| member | Member | 배치 수정 없음 |
| workspace | Workspace | 배치 수정 없음 |
| workspace | WorkspaceMember | 배치 수정 없음 |
| workspace | WorkspaceInvitation | 배치 수정 없음 |
| recording | RecordingSession | 배치 수정 없음 |
| recording | RecordingAudioUpload | 배치 수정 없음 |
| recording | RecordingAudioDeletionTask | 배치 수정 없음 |
| recording | RecordingTranscription | 배치 수정 없음 |
| recording | Transcript | 배치 수정 없음 |
| recording | TranscriptSegment | 배치 수정 없음 |
| document | Document | 배치 수정 없음 |
| document | DocumentConfirmation | 배치 수정 없음 |
| document | DocumentGenerationBatch | 첫 필드 앞 빈 줄 제거 |
| document | DocumentGenerationJob | 배치 수정 없음 |
| document | DocumentConfirmationId (`@Embeddable`) | 같은 필드 그룹 내부 빈 줄 제거 |
| search | SearchAnswerExecution | 첫 필드 앞 빈 줄 제거 |
| search | SearchConversation | 첫 필드 앞 빈 줄 제거 |
| search | SearchEvidence | 첫 필드 앞 빈 줄 제거 |
| search | SearchMessage | 첫 필드 앞 빈 줄 제거 |
| search | SearchRequestReceipt | 첫 필드 앞 빈 줄 제거 |
| search | SearchEvidenceId (`@Embeddable`) | 첫 필드 앞·같은 필드 그룹 내부 빈 줄 제거 |

`RecordingAudioDeletionTask`의 `@NoArgsConstructor(access = PROTECTED)`는 JPA 기본 생성자만 만든다.
검증하는 업무 생성자는 별도로 있어 도메인 행위를 숨기는 생성자 자동화에 해당하지 않는다.
이를 수동 생성자로 교체하거나 애노테이션 순서를 통일하는 변경은 포함하지 않았다.

## 검증

변경 전 document·search 도메인 단위 테스트 245건이 통과했다.
변경 후 백엔드 범위의 `git diff -w --ignore-blank-lines --exit-code`가 성공해 공백 외 변경이 없음을 확인했다.
별도 행위가 없어 테스트를 추가하거나 기존 테스트를 수정하지 않았다.

변경 파일 감사는 기존 `build.gradle` 버전 진단 2건만 남고 수정한 Java 파일의 진단은 없다.
전체 감사는 1,226건에서 1,217건으로 줄었다. 기존 진단 때문에 공통 검증기가 Gradle 전에 중단돼
`./gradlew spotlessApply spotlessCheck test integrationTest acceptanceTest bootJar --no-parallel`을 직접 실행했다.
5분 2초에 성공했으며 단위 1,545건·통합 676건·인수 567건이 실패·오류·생략 없이 통과했다.
Governance 검증기 단위 테스트 10건과 `git diff --check`도 통과했다.
