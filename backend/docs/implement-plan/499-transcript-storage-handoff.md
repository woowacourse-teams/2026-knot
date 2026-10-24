# 문서 원문 조회를 위한 전사 저장 계약

> 문서 도메인 #499의 원문 조회와 음성·전사 결과 저장을 연결하는 문서다. 사용자가 구간 저장 구조와 작성 담당을 확정했다. jyt6640이 Entity·테이블·조회 기반을 구현하고 음성 담당자가 실제 STT 결과 저장을 연결한다. Entity·테이블·JPA 저장/조회·문서 GET은 #499 브랜치에서 구현하고 로컬 검증했다. 실제 STT 결과 저장 실행·공급자 지원 검증은 아직 연결되지 않았다.

- 작성일: 2026-10-07.
- 연결: [#499 문서 원문 조회](https://github.com/woowacourse-teams/2026-Knot/issues/499), [#501 자동 문서 생성·보존 정리](https://github.com/woowacourse-teams/2026-Knot/issues/501).
- 기준: #499의 최신 계약, 사용자 결정, 붙여준 ERD 기준안, develop의 Transcript 모델. Notion 직접 게시·담당자 전송은 수행하지 않았다.
- 개선된 구현 순서·기존 API와의 차이는 [#499 개발 계획](499-document-transcript.md)에 정리했다. **(추가됨)**

## 목적

사용자가 문서에서 원문 보기를 열면 문서 생성에 사용한 회의 전체 글과 발언별 시각·화자·문장을 표시한다. 전체 글은 Transcript.content에서, 발언별 정보는 해당 Transcript에 연결된 구간 데이터에서 읽는다.

## 책임 분담 (이부분 수정됨)

| 작업 | 담당 범위 |
| --- | --- |
| STT 호출·TranscriptionJob 관리 | 음성·전사 도메인 |
| 실제 전체 원문·구간·타임스탬프 수집과 저장 | 음성·전사 도메인 |
| TranscriptSegment Entity·테이블·JPA 저장/조회 기반 | jyt6640이 확정한 구조로 먼저 구현하고 #499에서 사용 |
| Transcript → TranscriptionJob 연결 | 음성 담당 작업과 기존 문서 FK·조회 변경 범위를 맞춤. 구간 기반 작성과 별도 |
| 저장 완료 원문으로 AI 문서 생성 | 문서 도메인 #501 |
| Document.sourceTranscriptId로 전체 원문·구간 조회 | 문서 도메인 #499 |
| 보존 정리 중 성공 문서의 원문·구간 보호 | #501과 음성 자료 정리 작업의 공유 계약 |

#499는 조회 중 STT를 호출하거나 원문·구간을 생성·수정하지 않는다.

## 문서 API에 필요한 데이터 (이부분 수정됨)

| 필드 | 타입·NULL | 요구사항 |
| --- | --- | --- |
| transcriptId | Long · non-null | 해당 Document에 연결된 저장 원문 ID |
| transcriptText | String · non-null | 전체 원문. Transcript.content 사용 |
| recordingDurationSeconds | Integer · non-null | 원본 녹음 길이. 기존 서버 누적 녹음 밀리초를 초로 변환하고 소수 초는 버림 |
| isPartial | Boolean · non-null | 현재 최종 파일 하나 업로드 범위에서는 false |
| segments | Array · non-null | 발화가 있는 성공 문서 원문은 비어 있지 않은 실제 구간을 제공 |
| segments[].startMillis | Long · non-null | 최종 업로드 오디오 시작을 0으로 하는 실제 발언 시작 위치(ms) |
| segments[].endMillis | Long · nullable | 실제 종료 위치(ms). 모르면 null, 값이 있으면 startMillis 이상 |
| segments[].speakerNumber | Integer · nullable | 동일 원문 내 일관된 익명 화자 번호. 모르면 null |
| segments[].text | String · non-null | 해당 구간의 원문 문장 |

- 시각을 추정하거나 전체 텍스트에서 복원하지 않는다.
- 익명 화자를 실제 Member·녹음 참여자와 연결하지 않는다.
- 구간은 startMillis 오름차순, 동률이면 저장 순서로 조회한다.
- 구간 조회는 해당 transcriptId로 제한한다. JPA 메서드 후보는 `findAllByTranscriptIdOrderByStartMillisAscPositionAsc(transcriptId)`이며 테이블 전체의 `findAll()`을 사용하지 않는다. **(추가됨)**
- transcriptText는 저장된 전체 content다. 두 구간 예시의 전체 텍스트에는 “문서 보관 기준부터 정리하겠습니다.”와 “좋습니다.”가 모두 들어가야 한다. 조회 중 구간 텍스트로 재합성하지 않는다. **(추가됨)**
- 녹음 원문·문서 원문 API는 같은 transcriptId의 동일한 구간 데이터를 사용한다.
- 문서 원문에는 녹음 전사 진행 상태 필드를 가져오지 않는다. 전사 진행 중 응답과 저장 완료 문서 원문 계약을 구분한다.

## 확정 저장 모델 — TranscriptSegment (이부분 수정됨)

아래는 사용자 확정 구조이며 #499의 V31과 Entity로 구현했다. 운영 DB 적용과는 구분한다. 테이블명은 transcript_segments, 원문과 구간은 1:N 관계다. 저장 기반을 먼저 구현한 뒤 음성 담당자가 실제 데이터 저장을 연결한다.

| 컬럼 | PostgreSQL 타입 | NULL | 키·제약 | 의미 |
| --- | --- | --- | --- | --- |
| id | BIGINT GENERATED ALWAYS AS IDENTITY | NOT NULL | PK | 구간 ID |
| transcript_id | BIGINT | NOT NULL | FK → Transcript.id · ON DELETE RESTRICT | 소속 원문 |
| position | INTEGER | NOT NULL | CHECK ≥ 0 · UNIQUE(transcript_id, position) | 0부터 시작하는 원문 내 저장 순서 |
| start_millis | BIGINT | NOT NULL | CHECK ≥ 0 | 실제 발언 시작 위치(ms) |
| end_millis | BIGINT | NULL | CHECK: NULL 또는 start_millis 이상 | 실제 종료 위치(ms) |
| speaker_number | INTEGER | NULL | CHECK: NULL 또는 1 이상 | 익명 화자 번호 |
| text | TEXT | NOT NULL | CHECK: 공백만 있는 값 금지 | 구간 원문 |

- 조회용 인덱스 제안: (transcript_id, start_millis, position).
- 전체 텍스트와 구간을 같은 저장 트랜잭션으로 확정하고, 저장 완료 결과만 문서 생성 입력으로 공개한다.
- 최소 한 구간 존재 조건은 FK만으로 보장되지 않는다. 발화가 있는 성공 원문의 저장 완료·공개 시 검사한다.
- migration은 jyt6640이 #499에서 작성한다. 현재 develop의 V30 이후 `V31__create_transcript_segments.sql`을 작성했다. PostgreSQL/Testcontainers에서 적용했으며 병합 전 새 migration 충돌 여부는 다시 확인한다. 음성 담당자의 별도 schema 구현을 기다리지 않는다. **(이부분 수정됨)**
- 구간 공개 후 변경과 실제 저장 실행의 담당 이슈는 음성 담당 작업에서 맞춘다. 저장용 Repository를 제공하는 것과 실제 STT 결과 저장 사용 사례를 구현하는 것은 구분한다. **(이부분 수정됨)**

## 붙여준 API의 이전 제안 정리 **(추가됨)**

`startMillis=null`, 성공 원문의 `segments=[]`, “저장 포맷 미정”은 기존 API 초안의 잔여 문장이다. #499와 사용자가 확정한 저장 구조에서는 발화가 있는 성공 문서 원문의 시작 시각·실제 구간을 필수로 둔다. 녹음 전사 진행 중의 빈 배열을 문서 원문 성공 계약에 적용하지 않는다. 문서 응답의 transcriptId도 non-null이다.

실제 STT 지원은 아직 검증하지 않았다. 이미 전체 텍스트만 있는 운영 데이터가 있는지는 배포 전에 확인하고, 실제 구간이 필요하면 음성 담당자의 전환 작업으로 연결한다. 가짜 시간이나 구간을 만들지 않는다. 전체 텍스트·구간 누락은 DocumentException과 INVALID_TRANSCRIPT_DATA로 표현하고 HTTP 500을 반환한다. 사용자 요청에 따라 기존 도메인 오류 패턴으로 통일한 구현이며 실제 STT 저장 완료를 의미하지 않는다. **(이부분 수정됨)**

## ERD 연결과 현재 코드 차이

붙여준 ERD는 RecordingSession → TranscriptionJob → Transcript → TranscriptSegment 연결을 목표로 한다. Document는 sourceTranscriptId로 Transcript를 참조한다.

현재 develop의 Transcript는 recordingSessionId·content·createdAt을 갖고 RecordingSession에 직접 연결된다. TranscriptionJob 연결과 is_partial 컬럼은 추가하지 않았다. #499 브랜치에는 구간 모델을 추가했으며 실제 STT 저장 실행은 후속 작업이다. 목표 ERD를 이미 구현된 구조로 간주하지 않는다.

- 목표 구조로 변경할 때 기존 Document·Job의 원본 녹음·Transcript 연결이 깨지지 않도록 FK와 조회를 함께 검토한다.
- ERD의 is_partial에 남아 있는 조각 전사 설명은 최종 파일 하나 업로드 계약에 맞춰 정리한다. 미래 부분 전사 정책을 이번 문서에서 새로 정하지 않는다.
- 성공 Document가 참조하는 원문·구간은 오디오 30일 또는 다른 FAILED Job의 7일 만료로 삭제하지 않는다. 삭제 가능한 원문만 관련 참조·구간을 정리한 뒤 삭제한다.

## 음성 담당 작업과 맞출 항목

1. 실제 STT 공급자가 발언별 startMillis를 제공하는지 실제 결과로 확인한다. 종료 시각·화자 지원 여부도 확인한다.
2. jyt6640이 작성한 구간 저장 기반에 실제 STT 결과를 넣는 담당 작업을 연결한다. 기존 전체 글만 있는 데이터의 처리도 맞춘다.
3. Transcript → TranscriptionJob 연결을 바꿀지와 기존 문서 FK·조회 변경 범위를 맞춘다.
4. 전체 글·구간의 저장 완료 시점과 문서 생성에 넘길 원문 ID를 맞춘다.
5. 녹음·문서 조회에서 구간 순서·시간 단위·화자·null 규칙을 공통으로 유지한다.

#499는 확정 구간 저장 기반과 JPA 조회·HTTP 응답·권한·읽기 전용 검증을 먼저 진행할 수 있다. fixture 기반 조회 테스트와 실제 STT 결과 지원 검증은 별도로 보고한다.

## #499 로컬 검증 결과 **(추가됨)**

- Entity·DB 제약·원문별 정렬 조회·문서 연결·권한·JSON·OpenAPI·읽기 전용 및 DB 스냅샷 검증을 포함해 전체 Gradle 검사 1,315개와 Spotless·bootJar가 통과했다.
- 음성 담당자는 이 저장 기반에 실제 STT 결과를 넣는 실행 흐름을 연결한다. 현재 결과는 fixture 기반이며 실제 공급자 타임스탬프 지원 증거가 아니다.
