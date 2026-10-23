# 운영 Notion API 후보와 백엔드 정합성

현재 작업의 관련 원문·DB 속성은 [Notion 근거 탐색 기준](notion-context.md)에 따라 찾는다.
날짜별 대조는 해당 시점의 관측이다. 과거 검토 메모에서 현재 구현 상태를 추정하지 않는다.

## 현재 동기화 방식 · 2026-10-08

### #913 새 문서 큰 주제 · 2026-10-22 사용자 결정 대조

기존 목록 원문의 별도 Category 금지 규칙은 이번 작업에서 새 문서에 한해 대체한다.
안건별 문서·Job과 기존 자료는 유지하며 새 문서에 큰 주제 하나를 연결한다.
본문 이후 배정 장애는 제한 재시도·서버 규칙·최후 기타로 처리하고 문서와 함께 공개한다.
사용자는 새 API·응답 필드·FE 변경을 제외했다. 기존 topics와 카드 topic에 표시 이름을 제공하며
저장 안건 topic은 보존한다. 따라서 기존/새 이름이 같으면 화면의 문자열 그룹은 합쳐진다.
이는 사용자 결정과 로컬 구현의 대조이며 Notion 원문·queue를 원격 갱신했다는 의미는 아니다.
[실제 구현 계획](../../docs/implement-plan/document-broad-topic-assignment.md)과 [QA 결과](../../docs/qa/913-document-broad-topic.md)를 따른다.

### 수집·대조 순서

1. 운영 n8n 수집 성공과 본문·DB 속성의 revision을 확인하고 ignored 원문 캐시를 갱신한다.
2. 변경된 API와 관련 도메인·기획만 찾아 실제 내용을 읽는다. 내부 메타데이터는 제외하고
   메서드·경로·권한·요청/응답·오류·상태 전이 등 관련 규칙과 DB 속성 값을 추출한다.
3. 최신 API 우선 규칙과 기존 결정을 적용한 뒤 Controller·DTO·Security·Migration·Issue·ADR와 대조한다.
4. 검토한 현재 규칙·출처·미확정 사항을 해당 주제 문서나 `docs/implement-plan/`의 관련 계획에 반영한다.
   `implementation-plan-coach`도 같은 [탐색 기준](notion-context.md)을 따른다.
5. queue는 [정합성 워크플로](../../docs/harness/notion-alignment.md#queue-완료-조건)의 비교·반영 조건을
   만족한 뒤 처리한다. 캐시 수집이나 DB의 구현 표시만으로 완료 처리하지 않는다.

## 2026-10-13 홈 확인 카드·목록 정렬 충돌 대조

09:24:39 KST 시작한 실행 `698856`은 44.523초 뒤 성공했다. 같은 실행의 inventory와
시스템 열을 포함한 sources CSV로 캐시를 갱신하고 `--check` 일치를 확인했다.
앞선 실행 `687899`에서 발견한 변경 5페이지는 이번 수집에서도 동일했다.
아래는 원문·관련 DB 속성·Issue·ADR·로컬 코드를 읽은 결과이며 서비스 배포 확인은 아니다.

- **홈 표시 정책:** [HOME-R4](https://www.notion.so/3ddb4351752281229eb4cb22812ab6b1)의
  revision `2026-10-12T15:45:00Z`는 확인할 문서를 개수 제한 없이 표시하도록 정했다.
  높이 312와 제목을 유지하고, 공간을 넘으면 목록 영역만 스크롤한다. 스크롤 시작에 개수
  기준은 없으며 제목 숫자는 전체 개수, 99 초과는 `99+`, 0개는 숫자를 숨긴다.
  확인 완료 문서는 즉시 빠지고 전체 보기 링크는 두지 않는다.
  [CONF-R7](https://www.notion.so/3ddb435175228162a20fed6d4e66ad95), revision `15:35:00Z`도
  개수·순서를 HOME-R4에 위임한다. [HOME-02](https://www.notion.so/3ddb4351752281b0b8efe6b6ac60fa2a),
  revision `15:45:00Z`는 문서가 없어도 확인 카드와 최근 문서 카드를 유지하고 안내를 표시한다.
- **현재 조회 적용 기준:** 확인 카드는 `myConfirmation=PENDING` 필터를 사용한다.
  최신 API는 `size`를 한 응답의 크기로 정의하고 `nextCursor`로 끝까지 이어 조회하게 한다.
  전체 표시 정책을 페이지 없는 API 추가나 첫 페이지 크기 확대만으로 충족했다고 보지 않는다.
  `topics[].documentCount`는 필터된 전체 수이며 페이지 범위가 적용되지 않는다.
  생성 이후 가입자는 최신 API·기존 [Proposed ADR](../../docs/adr/496-document-my-confirmation-state.md)의
  `NOT_REQUIRED`를 유지한다. 홈 원문에 남은 후기 가입자 미정 문구로 확인 대상을 늘리지 않는다.
- 열린 [#405](https://github.com/woowacourse-teams/2026-Knot/issues/405)는 확인 카드에 PENDING,
  최근 카드에만 `size=3`을 명시한다. 확인 카드도 3개라는 기존 확정 계약으로 읽지 않는다.
  열린 [#585](https://github.com/woowacourse-teams/2026-Knot/issues/585)의 최근 문서 최대 3개는
  이번 HOME-R4에서도 유지된다. 현재 로컬 프론트에서 확인 카드 구현을 찾지 못했으므로
  전체 커서 연결·총수·확인 후 제거·내부 스크롤은 해당 카드 구현 시 대조할 항목으로 남긴다.
- **정렬 출처 충돌:** [목록 API](https://www.notion.so/3ebb435175228015bf4dc8d04281b461)의
  revision `2026-10-12T09:29:00Z`는 `createdAt` 내림차순·동률 `id` 내림차순이다.
  10/12 오전에 있던 녹음 시작 시각 정렬과 전달 필드·커서 보완 설명은 삭제됐다.
  반면 [DOC-R16](https://www.notion.so/3edb4351752281b2804dc5be8aaf76e3),
  [목록 UseCase](https://www.notion.so/dd8b4351752282e0913f81a76f2bef21), HOME-R4와
  [변경 로그](https://www.notion.so/3deb4351752281a99829f339cc9ceeb4)는 녹음 시작 시각순을 유지한다.
  [사용자 지정 최신 API 우선 기준](../../docs/product/current-v2-mvp.md#2026-10-06-최신-api-기준)에
  따라 현재 목록 조회·커서는 생성 시각·ID 계약을 적용한다. 녹음 시각순이 API에 반영됐다는
  아래 10/12 기록은 당시 관측이며 현재 적용 기준으로 사용하지 않는다.
- 로컬 `DocumentListJpaRepository`의 정렬·커서 경계와 `DocumentListService`의 다음 커서는
  생성 시각·ID이고, 닫힌 [#495](https://github.com/woowacourse-teams/2026-Knot/issues/495)와
  [기존 목록 계획](../../docs/implement-plan/495-document-list.md)도 같다.
  현재 API와 이 부분은 일치한다. 녹음 시작 시각 응답 필드·동률 키·커서 변경을 새로 확정하지 않는다.
  홈·문서 목록·사이드바의 기획 순서를 실제로 충족하려면 API·UseCase·기획의 정합 조정이 필요하다.
  목록 API의 DB 구현 YES와 본문의 controller 미구현 표시는 여전히 다르므로 상태값으로 판정하지 않는다.
- **사이드바 표시 보완:** 변경 로그 revision `15:45:00Z`는 헤더 아래 내용 영역 하나가
  남은 높이를 채우고 빈 상태·실패 안내를 그 영역의 가로·세로 가운데에 두도록 명시한다.
  Sidebar/Docs와 대화 없는 Sidebar/Chat에 같은 규칙을 적용한다. 기존 작은 실패 안내·행
  스켈레톤·독립 재조회 정책도 유지한다. 열린 [#609](https://github.com/woowacourse-teams/2026-Knot/issues/609)의
  시안 미확인·긴 실패 문구와 현재 V1 `WorkspaceSidebar`, 정렬·크기 인자가 없는 `InlineError`는
  후속 대조가 필요하다. 기존 권한 이동·재조회 실패 시 받은 목록 유지 계약은 그대로 적용한다.

pending 145개는 실제 후보별 비교·반영 확인 전까지 유지했다. 기능 코드·진행 중인 계획·ADR·
원격 Issue는 변경하지 않았으며, 캐시 일치를 정제된 문서나 구현 전체의 완료로 판정하지 않았다.

## 2026-10-12 문서 목록 정렬·사이드바 상태 대조

13:02:16 KST 시작한 실행 `671487`은 1분 20.472초 뒤 성공했다.
변경된 기획·도메인·API 10페이지의 본문과 필요한 DB 속성을 읽었다.
[문서 목록](https://www.notion.so/3edb4351752281b2804dc5be8aaf76e3)의 revision은
`2026-10-12T03:34:00Z`, [목록 API](https://www.notion.so/3ebb435175228015bf4dc8d04281b461)와
[목록 UseCase](https://www.notion.so/dd8b4351752282e0913f81a76f2bef21)는 `01:57:00Z`다.

- **확정된 표시 정책:** DOC-R15·R16은 주제 이름 오름차순, 주제 안 문서는 원본 녹음
  시작 시각 내림차순으로 정했다. 목록 날짜도 녹음 시작 날짜이며 문서 탭·사이드바는 같은
  순서를 사용한다. 생성 완료 시각·확인 여부로 재정렬하지 않고 같은 녹음의 최초 확정된
  표시 순서를 유지한다. 최신 API도 같은 정책을 반영했다.
- **미확정 기술 계약:** API 자체가 녹음 시작 시각 전달 필드·안정적인 동률 처리·커서 키의
  보완 필요를 명시한다. 응답 표·예시는 여전히 `createdAt`만 제공한다. 새 필드명·동률 키·
  커서 변경 방식을 임의로 정하지 않는다. 서로 다른 문자 종류의 주제명 정렬 세부 계약도
  이번 자료로 보장하지 않는다.
- **현재 로컬 구현 차이:** `DocumentListJpaRepository`는 주제에 `order by d.topic`,
  카드·커서 경계에 `d.createdAt desc, d.id desc`를 사용한다. `DocumentListService`의
  다음 커서도 `createdAt`·ID이며 `DocumentCardResponse`에는 녹음 시작 시각이 없다.
  `RecordingSession.startedAt` 저장 필드는 존재하지만 목록 전달·정렬에 사용한다는 증거는 아니다.
  닫힌 [#495](https://github.com/woowacourse-teams/2026-Knot/issues/495)와
  [기존 목록 계획](../../docs/implement-plan/495-document-list.md)은 이전 생성 시각 계약이다.
  새 정책 적용에는 해당 계약·조회·페이지 경계의 후속 비교가 필요하다. 기존 확인 상태
  [Proposed ADR](../../docs/adr/496-document-my-confirmation-state.md)은 이 정렬 결정을 다루지 않는다.
- **사이드바 상태 정책:** [문서 보기·원문](https://www.notion.so/3ddb43517522814c97e4e90111297141)과
  [에러 페이지](https://www.notion.so/3ddb43517522812bb4dcdf3a6f771ba3)의 revision `01:59:00Z`는
  DOC-R14·ERR-22를 추가했다. 트리를 본문·원문과 독립적으로 불러오고 재시도는 트리만 다시
  조회한다. 헤더·문서 라벨을 유지한 행 스켈레톤, 가운데 한 줄 빈 상태, 작은 실패 안내를
  사용한다. 실패 문구는 ‘목록을 불러오지 못했어요’, 문구 14·재시도 버튼 높이 30이다.
  변경 로그의 같은 날 수정이 앞선 왼쪽 빈 상태·긴 문구를 대체한다.
- 열린 [#609](https://github.com/woowacourse-teams/2026-Knot/issues/609)는 상태 시안 미확인과
  긴 실패 문구를 포함한다. 현재 `WorkspaceSidebar`는 V1 Notion 트리를 사용하고 별도
  로딩·실패·빈 상태 분기가 없다. `InlineError`에는 크기 변형이 없다. #609의 기존 401·403·404
  이동과 받은 목록 유지 계약을 새 상태 시안만으로 변경하지 않는다. 화면·Figma 픽셀 검증은
  수행하지 않았으며 원문의 시안 반영을 서비스 구현·배포 완료로 표현하지 않는다.
- DB의 목록 API 구현 YES와 본문의 미구현 표시는 다르고, UseCase도 구현 안 됨으로 남아 있다.
  상태 문자열로 구현을 판정하지 않는다. ‘래퍼런스’에 추가된 외부 연구 링크는 제품 정책으로
  반영하지 않았다. pending 137개는 그대로 유지하며 새 후보는 실제 계약·구현 대조가 필요하다.

## 2026-10-11 탐색 중지·재연결 API 대조

23:35:19 KST 시작한 n8n 실행 `653207`은 11.967초 뒤 성공했다.
[중지 API](https://www.notion.so/3ebb4351752280599f80c035a5644022)와
[재연결 API](https://www.notion.so/3ebb4351752280e886cdc57d0fdf292c)의 본문·속성 revision은
`2026-10-11T13:55:00Z`다. 이전 캐시에 빠졌던 Header·Path·응답·오류 표가 복구됐고,
DB의 구현 속성은 둘 다 YES다. 본문은 여전히 미구현이라고 적혀 있어 서로 다르다.

- [#568](https://github.com/woowacourse-teams/2026-Knot/issues/568),
  [중지 구현 계약](../../docs/api/search-answer-stop.md), 로컬 `SearchAnswerStopController`·
  `SearchAnswerExecutionService.stop`을 대조했다. 본문 없는 POST에 현재 소유·멤버십·답변 범위를
  검사하고 부분 content와 STOPPED를 저장한다. 이미 STOPPED인 중복 요청은 200이며,
  COMPLETED/FAILED가 먼저 확정됐으면 409 `ANSWER_NOT_RUNNING`이다.
  원문의 ‘STREAMING이 아니면 409’는 중복 STOPPED까지 거절하는 규칙으로 적용하지 않는다.
  원문의 먼저 확정된 종료 상태 반환 제안도 완료·실패에 200을 주는 새 결정으로 적용하지 않는다.
- 중지 원문은 Content-Type을 필수로 표기하지만 로컬 Controller는 요청 본문과 `consumes` 제한이
  없고 현재 구현 계약은 이를 선택 헤더로 명시한다. CSRF는 별도 필수 계약이다.
  저장된 중지를 200으로 반환하는 시점과 공급자 내부 생성이 실제 끝나는 시점은 구분한다.
- [#567](https://github.com/woowacourse-teams/2026-Knot/issues/567),
  [재연결 구현 계약](../../docs/api/search-answer-events.md), 로컬 `SearchAnswerEventsController`·
  `SearchAnswerSnapshotService`·`SearchAnswerReconnectConnection`을 대조했다.
  snapshot을 먼저 보내고 같은 실행의 저장 본문 증가분을 delta로 보낸다. 근거가 있으면 최대 3개
  카드를 보내며, 종료된 답변은 snapshot·근거·종료 이벤트 뒤 연결을 닫는다.
  전체 이벤트 replay·Last-Event-ID 복원·제외 문서 수 전송을 추가하지 않는 기존 계약을 유지한다.
- 원문의 제외 수 저장 설명은 현재 구현과 구분한다. `SearchMessage`와 별개인 실행 metadata에는
  excludedCount가 있고 snapshot 내부 결과에도 포함되지만, 재연결 전송 코드는 이를 내보내지 않는다.
  저장 존재를 FE에 제외 수를 다시 표시하라는 결정으로 해석하지 않는다.
- 원문의 근거 최대 3개는 반환 카드 제한으로 적용한다. 현재 사용자 결정인 실제 사용 근거 전체
  저장을 3개 저장으로 되돌리지 않는다. 두 Controller의 존재는 로컬 구현 근거이며,
  Notion의 YES·GitHub OPEN 상태로 develop 병합이나 운영 배포를 추정하지 않는다.

이번에는 소스·Issue·기존 계약을 읽어 대조했고 HTTP·모델·동시성 테스트는 새로 실행하지 않았다.
기능 코드·진행 중인 구현 계획·ADR은 수정하지 않았다. 캐시 `--check`는 일치, 누락은 없으며
기존 coverage 제한 26개는 동일하다. pending은 133행이고 verified 전환·삭제는 수행하지 않았다.

## 2026-10-11 영역 내 실패 안내 정렬 대조

22:31:24 KST 시작한 n8n 실행 `651734`은 16.169초 뒤 성공했다.
[에러 정책 ERR-09](https://www.notion.so/3ddb43517522812bb4dcdf3a6f771ba3)의
본문·속성 revision은 `2026-10-11T12:39:00Z`,
[변경 로그](https://www.notion.so/3deb4351752281a99829f339cc9ceeb4)는 `2026-10-11T12:40:00Z`다.

- 원문에 추가된 기준: `State/InlineError`는 해당 자리의 빈 상태 정렬을 따른다.
  홈 카드 4종·문서 목록 전체를 대신할 때는 안내와 그 아래 재시도 버튼을 가운데에 둔다.
  탐색 답변·원문 패널의 글 흐름 안에 들어갈 때는 왼쪽에 둔다.
- 로컬 `frontend/src/shared/components/primitives/ui/InlineError/index.tsx`는
  `align-items: flex-start`로 고정돼 있고 정렬 인자가 없다. 같은 디렉터리의 Storybook 설명도
  목록 실패를 포함해 왼쪽 정렬로 안내하므로 새 원문과 차이가 있다.
  가운데 정렬인 `RetryNotice`가 존재하지만, 이를 영역 내 실패 안내의 대체 컴포넌트로
  사용한다는 확정 근거는 없다. 컴포넌트·사용처·스토리의 대조가 필요하다.
- 조회 시 OPEN인 [#585](https://github.com/woowacourse-teams/2026-Knot/issues/585)는
  최근 문서 카드의 독립 재조회·401 전환 계약을 포함한다. 새 정렬 기준은 화면 표시 규칙이며,
  해당 요청·권한 계약을 바꾸거나 백엔드 endpoint·저장 필드를 추가하는 근거가 아니다.
  관련 ADR에서 정렬 결정을 찾지 못했으며 새 ADR이나 제품 규칙을 만들지 않았다.

이번 대조는 텍스트와 로컬 소스 기준이다. Figma 픽셀·실행 화면의 일치는 확인하지 않았고,
기능 코드·구현 계획은 수정하지 않았다. 캐시 `--check`는 일치, 누락은 없고 기존 coverage 제한
26개와 pending 131행은 동일하다. 후보의 verified 전환·삭제는 수행하지 않았다.

## 2026-10-11 홈 카드별 조회 실패 대조

20:27:52 KST 시작한 n8n 실행 `648827`은 17.416초 뒤 성공했다.
[홈 요구사항](https://www.notion.so/3ddb4351752281229eb4cb22812ab6b1),
[홈 화면](https://www.notion.so/3ddb4351752281b0b8efe6b6ac60fa2a),
[에러 정책](https://www.notion.so/3ddb43517522812bb4dcdf3a6f771ba3),
[변경 로그](https://www.notion.so/3deb4351752281a99829f339cc9ceeb4)의 본문·속성 revision은
`2026-10-11T11:26:00Z`다. HOME-R6·ERR-09의 홈 적용을 기존 API·Issue·로컬 코드와 대조했다.

- 실패 상태는 확인이 필요한 문서·최근 문서·진행 중인 녹음·팀원 초대의 4개 카드에 각각 적용한다.
  실패 카드의 제목·높이는 유지하고 내용에 안내와 다시 시도를 둔다. 다시 시도는 그 카드만
  요청하며 다른 카드를 사용할 수 있다. 홈 전체를 그릴 수 없을 때만 ERR-02·03을 따른다.
  HOME-07의 스켈레톤은 문서·최근 문서·녹음의 3개 카드이며 팀원 초대는 그대로다.
- 녹음 카드 조회 실패는 표시 실패다. 수집을 중단하거나 녹음 세션 상태를 FAILED로 바꾸는 근거가 아니다.
  로컬 `RecordingCurrentService`는 멤버십 검사 후 활성 세션을 read-only로 조회한다.
  [현재 녹음 API](https://www.notion.so/c45b43517522837aa1d081b71d43f645)의 종료 후 종합 상태 제안과
  로컬 활성 세션 조회의 차이는 기존 검토 대상으로 남긴다. 새 홈 실패 규칙으로 그 차이가 해결되지는 않는다.
- [#405](https://github.com/woowacourse-teams/2026-Knot/issues/405)는 미확인 문서에
  `myConfirmation=PENDING`, 최근 문서에 `size=3`을 사용한다.
  [목록 계약](../../docs/api/document-list.md)과 로컬 `DocumentController.findDocuments`·
  `DocumentListService`에는 해당 필터·페이지 크기·권한 검사 경로가 있다.
  새 화면 상태를 위해 홈 통합 endpoint나 저장 필드를 추가한다고 해석하지 않는다.
- 조회 시 OPEN인 [#585](https://github.com/woowacourse-teams/2026-Knot/issues/585)는 최근 문서 카드만
  재조회하는 실패 처리를 이미 포함하지만, 실패 시안이 없어 확인한다는 메모는 이번 원문 추가보다 이전 내용이다.
  401의 로그인 전환 계약은 유지한다. 카드 내 실패 표시는 권한 오류를 무시하고 데이터를 보여주는 허용이 아니다.
- 팀원 초대는 로컬 `WorkspaceInvitationController`의 `POST /invitations`로 새 코드·링크를 발급한다.
  HOME-R6의 다시 시도를 기존 초대 원문 GET이나 자동 재발급 허용으로 해석하지 않는다.
  현재 최소 MVP는 기존 초대 원문 재조회·채널별 재발급을 제외한다.
  카드의 요청·실패 분류와 발급 응답 유실 시 재시도 처리는 이 계약 안에서 FE·BE가 대조할 항목이다.

기존 ADR이나 권한·저장 계약을 새로 확정하지 않았다. 기능 코드·진행 중인 구현 계획은 수정하지 않았다.
이미지의 픽셀은 이번 텍스트 대조 범위에 포함하지 않는다. 캐시 coverage 제한 26개는 동일하며,
현재 pending은 131행이다. 캐시 갱신만으로 검증 상태 변경·완료 행 삭제를 수행하지 않았다.

## 2026-10-11 탐색 목록·첫 질문 API 대조

19:26:23 KST 시작한 n8n 실행 `647386`은 14.898초 뒤 성공했다.
아래 세 API의 본문·속성 revision은 `2026-10-11T09:59:00Z`다. 목록 조회와 첫 질문의
요청·응답 표가 이전 캐시의 헤더만 있는 상태에서 복구됐다. 이를 새 제품 결정으로 해석하지 않는다.
[메시지 조회](https://www.notion.so/3ebb435175228009b53bef6680719a88)는 revision과 속성이 바뀌었지만
본문은 이전 수집과 동일하다.

- [목록 조회](https://www.notion.so/3ebb43517522808ab9f9e8c5d7e5fb1b)의 size 기본 20·최대 100,
  `updatedAt DESC, id DESC`, 최초 USER 메시지에서 제목 도출, 회원·Workspace 소유 범위의 cursor는
  [#564](https://github.com/woowacourse-teams/2026-Knot/issues/564)의 기존 계약과 일치한다.
  로컬 `SearchConversationListParameters`도 기본 20·범위 1..100을 검사하고
  `SearchConversationListService`는 현재 회원·Workspace 권한과 cursor 범위를 확인한다.
  첫 답변 실패 대화는 목록에서 숨기고 재시도 성공 후 복구한다는 기존 계약을 유지한다.
- [첫 질문](https://www.notion.so/3ebb4351752280a39871f288fbc275b6)의 JSON 요청·SSE 응답,
  저장 commit 이후 `accepted`, 연결 종료와 독립된 생성, 실제 Document 근거 최대 3개는
  [#563](https://github.com/woowacourse-teams/2026-Knot/issues/563) 및
  [현재 구현 계약](../../docs/api/search-conversation-create.md)과 대조했다.
  로컬 Controller와 IntakeService에는 대화·USER·ASSISTANT·requestId receipt·실행 queue 생성이 있다.
  `requestId` 보관 기간과 질문 최대 길이는 노션에 미정으로 남아 있으나, 기존 결정은
  대화 보관 기간 동안 receipt 유지와 공백 포함 Unicode code point 2,000개다.
  [구현 계획](../../docs/implement-plan/563-search-conversation-create.md)의 확정 내용을 다시 미정으로 돌리지 않는다.
- 원문의 V2 미구현 문구는 현재 로컬 조회·질문 접수 코드의 존재와 다르다.
  다만 현재 구현 계약은 실제 검색 전략·색인 준비·모델 streaming adapter가 연결되지 않았고,
  기본 worker 비활성 상태에서 새 요청은 접수 전 `ANSWER_GENERATION_UNAVAILABLE`로 거절된다고 명시한다.
  코드 존재를 실제 검색 품질·운영 배포 완료로 보고하지 않는다.
  Proposed [ADR 271](../../docs/adr/271-llm-search-architecture-benchmark.md)의 과거 벤치마크 후보를
  현재 V2 검색 전략의 확정 결정으로 적용하지 않는다.

메시지 이력의 제외 문서 수는 보장하지 않고 첫 SSE 연결에서만 제공한다는 기존 계약을 유지한다.
노션의 보관·길이 미정 표시와 구현 상태 문구는 후속 원문 정합 대상이다.
이번 대조만으로 pending 후보를 완료 처리하지 않는다. 확인한 pending 행은 130개이며,
캐시 coverage 제한 26개는 이전과 동일하다.

## 2026-10-11 녹음 단건 조회 계약 대조

18:23:21 KST 시작한 n8n 실행 `645917`은 8.376초 뒤 성공했다.
[녹음 단건 조회 원문](https://www.notion.so/3deb4351752280a4a9a3f04bff7d0ee4)의 본문과
속성 revision은 모두 `2026-10-11T09:22:00Z`다. 변경 본문을
[PR #546](https://github.com/woowacourse-teams/2026-Knot/pull/546)의 DTO·Service·조회 코드 및
[#386의 범위 정리](https://github.com/woowacourse-teams/2026-Knot/issues/386#issuecomment-6077983404)와 대조했다.

- `GET /api/v1/workspaces/{workspaceId}/recordings/{recordingId}`는
  `status`, `elapsedMillis`, `endedAt`, `endReason`, `audioUploadStatus`만 제공한다.
  status는 세션의 `RECORDING`·`PAUSED`·`ENDED`·`DISCARDED`이며,
  전사·문서 생성의 종합 상태나 Job·실패 필드는 포함하지 않는다.
  업로드 상태는 예약 전 `null`, 예약 후 `RESERVED`, 저장 확인 후 `COMPLETED`다.
  `RESERVED`만으로 파일이 업로드되지 않았다고 단정하지 않는다.
- 조회는 현재 Workspace 소속과 본인 녹음을 검사하며 생존 시각·제어 증명·세션을 변경하지 않는다.
  PR의 `RecordingDetailService`는 다른 회원 녹음에 403, 다른 Workspace 또는 없는 녹음에 404를 반환한다.
  세션과 업로드 예약은 한 JPQL 조회로 읽는다. 기존 `/recordings/current`의 종료 후 204는 유지한다.
- 최초 탭은 heartbeat 응답, 다른 탭·화면은 단건 HTTP 조회로 상태를 받는다.
  같은 recordingId의 ENDED·DISCARDED를 확인한 뒤 늦은 활성 응답으로 되돌리지 않는다.
  ENDED는 수집 중단 후 저장 흐름, DISCARDED 및 Workspace 권한 상실 403·404는 수집 중단과
  임시 버퍼 폐기다. 녹음 상태를 보는 권한은 최초 탭 제어권을 넘기지 않으며,
  [#530 종료 제어 결정](../../docs/api/recording-end.md)과도 구분한다.
- 9/30의 종합 상태·전사·문서 Job·실패 코드 제안은 원문에서 이전 계획으로 명시됐다.
  현재 단건 조회 구현 계획에 그 필드를 다시 넣지 않는다. #386의 10/9 코멘트에 남은
  `serverNow` 공통 필드 문구도 최신 원문과 PR DTO의 5개 필드 계약과 다르므로 후속 Issue 정합 대상으로 남긴다.
- 확인 시 PR #546은 OPEN·미병합이며 head는 `df8c1af7730f012ba6fcd2ad1f2c304ed4d4590e`다.
  이 checkout에는 `RecordingDetailController`·`RecordingDetailService` 및 해당 계약 문서가 없다.
  원격 PR 구현을 로컬 적용·배포 완료로 보고하지 않는다. PR 작성자의 테스트 결과는 이번 실행의 검증 결과가 아니다.
  #386은 OPEN이며 30초 heartbeat만으로 정상 통신의 종료 관측 5초 목표를 충족했다고 볼 수 없다.
  추가 조회 주기·실제 FE 수집 정지·새로고침 복구와 #385의 실제 2시간 상한은 별도 검증 범위다.

이번 변경은 기존 복구 ADR을 새로 확정하거나 pending 후보를 완료 처리하는 근거가 아니다.
캐시 `--check`는 일치했고 기존 coverage 제한 26개는 동일하다.

## 2026-10-11 녹음·원문·화면 기획 대조

14:26:06 KST 시작한 n8n 실행 `640407`은 37.762초 뒤 성공했다. 같은 실행의
전체 검색 속성과 sources를 대조해 기획 12페이지의 변경을 읽었다. API·도메인 본문에는
이번 입력에서 변경이 없었다. 아래는 백엔드 작업에 필요한 선별 결과다.

### 녹음 제어: 기존 결정과 일치한 부분과 남은 차이

[다른 탭·기기 정책](https://www.notion.so/3edb4351752281799493d1e055be8327)은
10/11 13:44 KST 수정됐으며 DB 상태는 `검증 전`이다.
[변경 로그](https://www.notion.so/3deb4351752281a99829f339cc9ceeb4)의 같은 날 항목과
[로드맵](https://www.notion.so/3e2b4351752281f1b470cbc00fa37ae0)을 함께 읽었다.

- STT-R28·STT-20은 다른 탭·기기에 홈 카드와 독의 상태·경과 시간만 보여준다.
  녹음 화면 진입·일시정지·재개·종료는 허용하지 않고 직접 URL 진입도 홈으로 보낸다.
  다른 탭의 녹음 화면·종료(STT-17)는 이후 버전으로 옮겼다. 로드맵 DB 구분도 `이후 버전`이다.
- 최초 탭만 종료한다는 방향은 이미 확정된 [#530](https://github.com/woowacourse-teams/2026-Knot/issues/530)과
  [현재 종료 계약](../../docs/api/recording-end.md)에 일치한다. 로컬 `RecordingEndController`는
  `{tabId, controlToken}` 본문을 받고 `RecordingEndService`는 소유자·제어 증명을 검증한다.
  #530은 조회 시 OPEN이었다. 코드 존재를 배포·FE 연동 완료로 해석하지 않는다.
- 운영 [종료 API](https://www.notion.so/3dfb43517522806c88ecc6f5376c79e0)는 여전히 본문 없음·다른 탭 종료 허용이다.
  #381과 Proposed [ADR 378](../../docs/adr/378-recording-tab-recovery-and-expiry.md)도 이전 허용을 남긴다.
  이 항목은 10/8 사용자 결정으로 대체됐으므로 기존 제어 증명을 제거하지 않는다.
  운영 API·기존 Issue·ADR의 대체 관계 동기화가 남았다.
- 새 기획의 새로고침 종료·로드마다 새 탭 ID는 #378·ADR 378의 같은 탭 증명 유지·복구와
  여전히 다르다. 이번 기획 변경을 새로고침 복구 폐기의 승인으로 취급하지 않는다.
  종료 신호 유실 시점도 기획에 미정으로 남아 있다. 무음 자동 종료를 원격 종료의 확정 대안으로
  쓰지 않는다. 현재 제품 기준에서 무음 정책은 미정이다.

### 시작·종료 실패 안내: 서버 결과와 화면 안내의 구분

[시작·끝내기](https://www.notion.so/3edb43517522813d9fcfdcf2dea0831b)는
10/11 13:24 KST 수정됐으며 DB 상태는 `개발 중`이다.
[에러 페이지](https://www.notion.so/3ddb43517522812bb4dcdf3a6f771ba3)의 ERR-18~21도 대조했다.

- STT-R29는 네트워크 시작 실패에 재시도, 그 밖의 실패에 새로고침을 안내한다.
  응답 없음·타임아웃과 4xx의 분류는 원문에서도 기본안이며 5xx 분류는 확정되지 않았다.
  R28의 중복 시작 토스트와 R29의 모든 비네트워크 오류 다이얼로그도 서로 다르다.
- `RecordingStartService`는 같은 요청 키·Workspace·탭·증명의 재요청에 기존 상태를 반환하고
  활성 중복 또는 같은 키의 다른 입력을 거절한다. 종료된 같은 요청을 오류로만 보는 화면 설명과
  [#378](https://github.com/woowacourse-teams/2026-Knot/issues/378)의 ENDED 재반환 계약은 구분해야 한다.
  재시도에서 새 키를 생성하는 규칙이나 새 서버 오류 코드를 만들지 않는다.
- ERR-21은 종료 실패 시 계속 녹음하며 다시 종료하도록 안내한다. 응답 유실만으로 서버가
  아직 RECORDING이라고 확정할 수는 없다. 현재 종료 API의 동일 증명 재시도·endedAt 불변 계약을
  유지하고, 상태 재조회와 실패별 화면 매핑은 FE·BE 연동에서 확인할 항목으로 남긴다.

### 원문과 로딩: 화면 요구를 API 변경으로 확대하지 않음

[문서 보기·원문](https://www.notion.so/3ddb43517522814c97e4e90111297141)의
10/11 13:24 KST 수정과 DB 상태 `개발 중`을 확인했다. DOC-R13·ERR-20은 원문 패널만
로딩·실패·재시도로 바꾸고 문서 본문을 유지하며, 원문 없는 404도 같은 패널 안내를 사용한다.
이는 HTTP 성공으로 바꾸거나 권한 오류를 무시하라는 계약이 아니다.

[문서 원문 API](https://www.notion.so/3ebb4351752280528c05e37e9135d96a)와
`DocumentController.findTranscript`·`DocumentTranscriptService`를 대조했다.
현재 코드는 Workspace 멤버를 먼저 검사하고 연결 원문이 없으면 `TRANSCRIPT_NOT_FOUND`,
저장된 본문·구간 계약 위반이면 `INVALID_TRANSCRIPT_DATA`를 반환한다.
원문의 “controller 없음·구간 저장 미정”은 현재 코드와
[#499 계획](../../docs/implement-plan/499-document-transcript.md)의 확정 범위와 다르다.
조회 코드 존재는 실제 STT 저장 연결·배포 성공의 증거가 아니다. DOC-R7의 참여자 번호는
Workspace 멤버와 연결하지 않는 기존 익명 화자 계약으로 읽는다.

10/11 변경 로그 및 해당 화면 본문에서 확인한 로딩 범위는 다음과 같다.
문서 목록(DOC-06)은 폴더·행, 문서 상세(DOC-07)는 문서와 원문 패널을 각각 불러온다.
홈(HOME-07)은 세 카드별 독립 로딩·실패, 녹음(STT-19)은 상태 수신 전 제어 비활성이다.
지난 대화(SEARCH-15)의 메시지 로딩은 답변 생성 중 화면과 구분하며,
로그인(AUTH-03)은 OAuth 복귀 처리 중 버튼을 비활성화한다.
이 화면 상태 추가만으로 새로운 BE endpoint나 저장 필드를 요구하지 않는다.
근거는 [변경 로그](https://www.notion.so/3deb4351752281a99829f339cc9ceeb4)와 연결된 화면 문서다.

캐시 입력 일치는 확인했지만 새 이미지가 포함된 화면 5개의 픽셀은 검토하지 않았다.
첨부·unsupported 제한은 총 26페이지이며 텍스트 요구 대조와 시각 검토 완료를 구분한다.
수집 후 pending 테이블은 126행이었다. 미정·원문 차이가 있어 이번 실행에서 후보 상태 변경이나
삭제는 하지 않았으며, 모든 후보의 의미 검토가 완료된 것으로 보고하지 않는다.

## 2026-10-09 문서 확인 API 재수집 대조

02:42:53 KST 시작한 n8n 실행 `561506`이 성공했다. 문서 관련 API 7개의 본문
revision은 `2026-10-08T17:06:00Z`인데 같은 실행 Search 출력의 속성 revision은
9/30 또는 10/6~10/7로 남아 있다. `revision_mismatch`이므로 최신 정합 완료로
판정하지 않고 후보를 유지한다. 기존 캐시 이력은 보존했다.

5개 API는 이전 본문과 같고 캡처 시각·수집 상태만 달라졌다. 실제 본문 차이는
[확인 현황 조회](https://www.notion.so/3ebb43517522802da344e8f8eedd6849)와
[내 확인 처리](https://www.notion.so/3ebb4351752280fd8377e25d15fa3380)의 표 행이
추가 수집된 것이다. 이를 새 제품 결정으로 취급하지 않는다.

- 조회 표의 대상·집계·nullable 필드와 cursor/size는 기존
  [#497](https://github.com/woowacourse-teams/2026-Knot/issues/497) 계약과 같다.
  원문에서 기본 50·최대 100과 정렬은 여전히 제안이다. 현재 구현 계약은 Issue와
  `DocumentConfirmationService`·`DocumentController`에 따른다.
- 확인 표의 비대상 `409 CONFIRMATION_NOT_REQUIRED`, 최초 시각 유지, 확인·집계·보관의
  트랜잭션은 [#498](https://github.com/woowacourse-teams/2026-Knot/issues/498) 및
  `DocumentConfirmationCommandService`의 현재 코드와 대조했다.
- 두 원문의 “API/controller 없음” 문장은 현재 코드와 다르다. GET/PUT Controller와
  서비스가 존재하고 두 Issue는 조회 시 CLOSED였다. 배포·테스트 통과까지 확인한 것은 아니다.
- 목록·상세의 내 확인 3상태는 [ADR #496](../../docs/adr/496-document-my-confirmation-state.md)의
  기존 결정에 따른다. 확인 현황 API의 Boolean과 구분하며 이번 수집으로 변경하지 않는다.

본문과 속성 revision이 일치하는 수집 근거를 확보한 뒤 queue 완료 여부를 다시 판정해야 한다.
이번 대조에서는 원문·기능 코드·진행 중인 #524 계획과 후보 상태를 변경하지 않았다.

아래 과거 관측은 이력으로 보존한다. 현재 API와 구현 상태를 재검토한 결과가 자동으로 갱신된 것은 아니다.

## 2026-10-01 검토 이력

확인일: 2026-10-01
상태: 검토 메모. API 초안 승인이나 구현 완료 기록이 아니다.

이 문서는 운영 Team Notion에서 감지된 API 변경 후보를 백엔드의 현재 계약과 구분한다.
제품 범위와 충돌 처리에는 [현재 V2 MVP 기준](../../docs/product/current-v2-mvp.md)과
[Notion 정합성 워크플로](../../docs/harness/notion-alignment.md)를 함께 적용한다.

## 근거와 확인 범위

- 2026-10-01 14:32 KST n8n Data Table UI에서 741개 source와 37개 pending row를 읽었다.
  마지막 capture는 9/30 16:16 KST이고 Data Table의 마지막 `updatedAt`은 9/30 16:25:18 KST다.
  `Knot Notion Change Queue` 편집기에 `Publish`가 보여 수집 workflow는 게시되지 않은 상태였고,
  새 capture는 확인되지 않았다. 별도 `Knot Notion Queue Completion`은 게시된 5분 예약 workflow다.
  이 대조 전 queue 후보는 모두 `pending`이었다. 10/1 15:00 KST 재확인에서는 전체 37개 중
  14개가 `processed`, 23개가 `pending`, `verified`는 0개였다. 인증 API 네 후보는 아직 pending이며,
  최신 전체 상태와 row별 사유는 하네스 checkpoint에서 확인한다.
- 10/1 15:24 KST에 미게시 capture workflow를 수동 실행했지만, `Get many child blocks`가 429
  rate limit 오류로 실패했다. 두 `Upsert row(s)` node에는 도달하지 않아 Data Table은 갱신되지
  않았다. 따라서 9/30 16:16 KST capture가 여전히 최신 snapshot이다.
- 2026-09-28 API 상세 snapshot에는 operation 41개가 있었고, Notion의 구현 표시는
  `YES` 10개, `NO` 31개였다. 이는 당시 Notion 값이며 현재 코드 검증 결과가 아니다.
- 백엔드 Java 소스의 Controller mapping을 검색했을 때 `auth`, `workspace`, `invitation`
  영역은 확인했지만 Recording 및 Document controller mapping은 찾지 못했다. 실제 노출
  여부를 확정할 때는 코드뿐 아니라 실행 환경의 OpenAPI도 다시 확인한다.
- 이전 OpenAPI 관측은 개발 서버 기준 2026-09-28, 26 operations다. 이 시점 뒤의 runtime
  계약은 아직 이 문서에서 확인하지 않았다.
- `(신)도메인`의 `도메인 규칙 및 UseCase` page ID는
  `3e3b4351-7522-80cd-9f06-edc2ccdff1d8`, source row 161,
  `last_edited_time=2026-09-30T06:53:00Z`, `snapshot_hash=eb25bb84`,
  `captured_at=2026-09-30T07:16:02.692Z`다. 큰 snapshot은 Data Table 셀 대신 로그인된
  Notion 페이지를 열어 Member·로그인 규칙을 읽었다. 연결된 `로그인 · 온보딩 (v2)`는
  `개발 중`이며 닉네임 입력 중 검증·오류 문구·퍼널 배치 등 화면 규칙을 담는다.
- 10/1 14:21 KST 직접 연 도메인 본문은 GitHub OAuth, Member 1:1, nickname 입력 후 가입 완료,
  nickname 형식, 기본 GitHub 이미지와 여러 Workspace 참여를 적는다. Access·refresh lifetime은
  도메인 본문에 없으며 같은 도메인의 `아직 정하지 않은 것`은 OAuth session/token 갱신·만료와
  가입 중단 후 재진입을 미정으로 적는다. 9/30 변경 로그에는 로그인 규칙 수정 항목이 없다.
  10/1 열린 #427–#430은 사용자가 확인한 구현 방향을 나눠 기록하지만 Notion 페이지 간 표기와
  팀 승인은 별개다.
- 같은 도메인의 `아직 정하지 않은 것` child page는 n8n row 191, ID
  `3e3b4351-7522-8051-bc18-c34052dbbd4f`, `last_edited_time=2026-09-23T05:26:00Z`,
  `snapshot_hash=84999f04`다. 현재 열린 페이지에는 OAuth 세션·토큰 갱신·만료와 가입 중단
  후 재진입이 미정으로 적혀 있다. 이 스레드에서 앞서 제공한 발췌에는 중복 요청 멱등성과
  닉네임 중복·변경 정책도 미정으로 있었으나, 현재 열린 페이지에서는 확인되지 않아 차이로
  남긴다.
- 로그인 관련 pending API 행은 OAuth callback (`4bfb4351-7522-822e-a25c-81a034b85b8d`,
  `changed`, source edit `05:18Z`, hash `ce85e1bc`), 닉네임 설정
  (`3ebb4351-7522-802f-b8a1-e43c6234d470`, `new`, `05:41Z`, `c520072e`), logout
  (`482b4351-7522-835e-a4fc-81e24c8fd2a9`, `changed`, `05:15Z`, `9701d3bf`), refresh
  (`3ebb4351-7522-80a5-ba6a-f3743703998b`, `new`, `04:58Z`, `4d806547`)다. 네 행 모두
  `detected_at=2026-09-30T07:16:02.692Z`, 현재 `status=pending`, `claim_id`와
  `lease_until`은 비어 있다. API root와 로그인 database도 별도 `changed` 후보로 남아 있다.
- Refresh source row 739와 pending candidate는 9/30 16:16 KST capture다. 10/1 15:22 KST에
  직접 확인한 live `/api/v1/auth/refresh` page는 204, access 1시간, idle 7일·absolute 30일,
  회전, 재사용과 logout 때 현재 family 폐기를 적는다. 삭제·정지 시 모든 family 폐기와 token
  기록 보존·정리 규칙도 추가돼 있다. 9/30 candidate와 비교하면 candidate에는 `403 CSRF_INVALID`
  설명과 access·refresh cookie 예시가 있지만 live page의 403 행은 비어 있고 refresh cookie 예시만
  보인다. 반대로 삭제·정지 폐기와 정리 규칙은 candidate에 없다. API page의 구현 속성은 `NO`다.
  #430은 핵심 7일·30일·회전·family 재사용 범위를 적지만 open이며 checklist가 미완료다. 이
  원문·snapshot 차이와 도메인 미정 page를 정리하기 전까지 candidate는 `pending`으로 둔다.
- GitHub #427–#430은 모두 10/1 관측 시점에 열려 있다. #427은 기존 Member OAuth login과
  신규 nickname token 발급, #428은 현재 session logout, #429는 nickname 검증·가입 완료,
  #430은 refresh endpoint의 만료·회전·reuse 처리를 기록한다. #427과 #430은 사용자 확인을
  본문에 명시하지만 Issue open 상태이므로 팀 리뷰 승인이나 구현 완료는 아니다.

## 2026-09-30 변경 후보

| 주제 | 변경 후보의 내용 | 구현 계약으로 쓰기 전 확인할 점 |
| --- | --- | --- |
| 녹음 종료 | `/recordings/{recordingId}/end`는 세션을 `ENDED`로 전이하고 업로드 확인이나 STT 접수는 수행하지 않음 | 인증·시작자 권한, 상태 전이, 중복 호출, 동시 종료 결과 |
| 최종 오디오 | upload URL 발급 뒤 클라이언트가 최종 파일을 PUT하고, 별도 `/audio-upload-complete`가 저장 객체의 존재·크기·Content-Type을 확인한 뒤 STT를 접수 | object storage 제공자, 크기·시간 제한, 소유권 검증, 완료 통지 멱등성, 실패 시 재시도 |
| 처리 상태 | `sessionStatus`, `audioUploadStatus`, `transcriptionStatus`, `documentGenerationStatus`, `documentGenerationJobId`, `failureStage`를 분리 | 허용값·상태 전이·Job 식별 기준·공개 가능한 안정 오류 코드 |
| 전사 구간 | 원문 구간을 `startMillis`, `endMillis`, `speakerNumber`, `text` 배열로 제공하고 데이터가 없으면 `segments: []` | STT provider가 보장하는 timestamp·speaker, 저장 형식, 기존 `Transcript.content TEXT`와의 관계 |
| 탐색 명칭 | 사용자-facing 용어와 API 영역에서 `exploration` 대신 `search` 사용 | 현재 공개 경로 및 FE 호출 계약과의 호환성 |
| 인증·로그인 | OAuth callback, 닉네임 설정, refresh, logout 변경 후보가 대기 중이며 cookie·수명·가입 흐름을 함께 다룸 | 각 후보 상태와 현재 코드의 부분 구현은 아래 인증 섹션 참고 |

### API root 피드백과 endpoint 제안 요약

9/30 `/ (신)API 명세서` candidate에는 API 공통 인증·오류 규칙과 아래 피드백 반영 메모가 있다.
이는 개인 명세의 제안 상태이며 endpoint 구현 또는 FE 합의를 뜻하지 않는다.

| Endpoint 또는 주제 | candidate에 추가·정리된 내용 | 저장소 대조 상태 |
| --- | --- | --- |
| `GET /workspaces/{workspaceId}/documents` | `myConfirmationState`를 `PENDING`, `CONFIRMED`, `NOT_REQUIRED`로 구분하고 `myConfirmation` query로 필터링. 주제별 전체 개수와 cursor 기반 문서 목록을 제공하며 전체 비페이지 조회는 추가하지 않는 제안 | 문서 confirmation 범위와 페이지 크기 근거는 제품·FE 검토 후보. 구현 완료로 보지 않음 |
| `GET /recordings/current`, `GET /recordings/{recordingId}` | 9/30에는 전사·문서 생성 상태와 Job·실패 필드를 제안했으나, 10/11 단건 조회 원문은 세션·업로드의 5개 필드로 대체하고 이전 계획임을 명시 | [10/11 단건 조회 대조](#2026-10-11-녹음-단건-조회-계약-대조) 적용. current는 종료 후 204 유지. PR #546 미병합·FE 연동 검증 별도 |
| `GET /recordings/{recordingId}/transcript`, `GET /documents/{documentId}/transcript` | `transcriptId`, 전체 원문과 공통 `segments` 구조, `startMillis`, `endMillis`, `speakerNumber`, `text`를 제안 | 현 STT 저장 포맷과 성공 시 실제 timestamp 보장은 미정 |
| `GET /document-generation-jobs` | `QUEUED`, `RUNNING`, `FAILED` 작업을 cursor 기반으로 복원·조회하는 제안 | 목록·재시도와 녹음 응답의 job ID 연결은 구현 전 확인 |
| 탐색 이름 | 사용자-facing 용어 및 API 영역에서 `exploration` 대신 `search`로 통일하는 팀 메모 | 기존 endpoint와 계약 전환 범위는 검색 도메인에서 별도 확인; `frontend/`는 이번 작업에서 수정하지 않음 |

9/30 API root의 feedback note는 확인 상태, 홈의 pending 전용 필터, 로그인 연장, 문서 job ID,
failure reason 코드, 전사 segment, 문서 목록 페이지 크기, JSON 예시를 다룬다. 후보 endpoint 일부는
이 피드백을 반영한 상세를 담지만, `처리 완료` 문구나 candidate 변경 상태만으로 팀 승인이나 코드
구현을 추론하지 않는다. 별도 API 페이지가 제목만 수집된 경우에는 본문이 비어 있는지 실제로
수집되지 않은 것인지 확인될 때까지 기능 계약을 보완하지 않는다.

후보 세부 필드의 예시와 상태는 확인 편의를 위한 것이다. 이 메모의 표만으로 request/response
schema, DB 상태, 비동기 실행 정책을 확정하거나 코드를 변경하지 않는다.

### 인증·로그인: 2026-09-30 후보와 10/1 Issue 대조

#### 도메인 기준

- GitHub OAuth로 가입·로그인하고 GitHub 계정 하나는 Member 하나와 연결한다.
- 로그인 뒤 닉네임 입력을 완료해야 가입이 끝난다. 닉네임은 20자 이하이며 한글·영문·`(`,
  `)`, `-`만 허용하고 공백은 금지한다. 서버도 같은 기준으로 검증한다.
- GitHub 프로필 이미지를 기본값으로 사용하고 Member는 여러 Workspace에 참여할 수 있다.
- 도메인 규칙 페이지에는 탈퇴 계정의 재로그인 제한이 없다. `/oauth2/authorization/github`
  pending candidate는 탈퇴 처리된 Member를 다시 로그인시키지 않는다고 제안하지만, 이를 현재
  도메인 합의로 승격하지 않는다.
- 현재 열린 `아직 정하지 않은 것` 페이지는 OAuth 세션·토큰 갱신·만료, 가입 중단 후 재진입을
  미정으로 둔다. 앞서 제공된 발췌와 닉네임 중복·변경 정책의 포함 여부가 다르므로 그 두
  미정 항목은 source discrepancy로 남긴다. 사용자는 10/1 Issue #427–#430 본문에서 login,
  logout, nickname completion, refresh 방향을 확인했다. 이는 현재 task의 사용자 지시로 반영하되,
  Notion 문서 간 충돌이 정합됐거나 팀 리뷰가 끝났다고 표현하지 않는다.

#### 로그인 API candidate가 제안하는 흐름

아래는 `knot_notion_pending` 캡처에 기록된 후보 내용이다. 대조 시작 시 네 endpoint가 모두
`pending`이었고, 아래 Issue는 구체적인 작업 계약을 기록하지만 승인된 API spec으로 단정하지 않는다.
API root의 공통 문구는 access 인증에
`__Host-KNOT_ACCESS_TOKEN`을 쓰고 변경 요청에 `X-XSRF-TOKEN`을 요구하며 오류 응답을
`code`, `message`, `fieldErrors`로 구성한다.

| 후보 endpoint | 캡처에서 제안한 내용 |
| --- | --- |
| `GET /oauth2/authorization/github` | 인증 헤더 없이 브라우저 이동, GitHub `302`와 `state` 검증. 기존 활성 Member에는 access·refresh cookie 발급. 신규 사용자는 10분 `KNOT_NICKNAME_TOKEN`만 받고 닉네임 입력으로 이동. GitHub access token은 browser storage에 노출하지 않음. 탈퇴 Member 재로그인 제한도 이 후보에만 있음 |
| `POST /api/v1/auth/nickname` | JSON `{ "nickname": "octocat" }`, 온보딩 cookie와 `X-XSRF-TOKEN` 필요. 공백 입력·20자 초과, invalid body, 만료/부정확 token, 중복 완료 오류를 제안. 성공은 204로 access·refresh cookie를 발급하고 온보딩 cookie를 만료. 후보 유효성 설명은 공백·길이만 적어 도메인의 허용 문자 규칙보다 좁음 |
| `POST /api/v1/auth/refresh` | body·path·query 없음, `__Host-KNOT_REFRESH_TOKEN`과 `X-XSRF-TOKEN` 필요. 204와 새 access·refresh cookie. access 1시간, refresh는 마지막 발급/갱신 뒤 미사용 7일 또는 최초 OAuth 로그인 뒤 30일 중 먼저 도달한 시각에 만료 |
| `POST /api/v1/auth/logout` | 선택 access·refresh cookie와 필수 `X-XSRF-TOKEN`, 204. access·refresh·온보딩 cookie를 만료하고 유효 refresh가 있으면 현재 로그인 family만 폐기. 다른 기기는 유지. Stateless access JWT는 폐기되지 않아 최대 1시간 더 유효할 수 있음. 반복 호출도 204 제안 |

공통 API candidate는 access·refresh cookie의 `Secure`, `Path=/`, `Domain` 생략을 적고,
OAuth·nickname 후보는 HttpOnly도 적는다. Refresh API 본문은 요청마다 refresh를 교체하고,
동시 요청은 하나만 성공하며 이미 사용된 token 재제출은 해당 family 재사용으로 처리한다고
설명한다. 재사용 기록은 family의 절대 만료까지 보관하고 만료 후 7일 뒤 정리할 수 있다고
한다. 오류 후보는 refresh의 `401 UNAUTHENTICATED`/`403 CSRF_INVALID`; logout의 CSRF 실패
`403`이다. `SameSite=Lax` 등 개별 cookie 속성은 해당 endpoint 원문에 명시됐는지 확인해
사용하며 다른 후보 페이지에서 가져와 일괄 규칙으로 만들지 않는다.

Notion API root의 refresh row는 `구현=NO`로 표시한다. `/nickname`, OAuth callback, logout
candidate의 `NO`/`YES` 값은 Notion metadata일 뿐 실제 code state를 확정하지 않는다.

#### 현재 checkout 구현과 Issue 상태

- 현재 worktree의 [`AuthController`](../src/main/java/com/knot/backend/auth/presentation/AuthController.java)는
  `/me`, `/csrf`, `/nickname`을 제공하며 `POST /refresh` mapping은 없다.
- 기존 활성 Member OAuth login은 `AuthService`에서 access·refresh token을 만들고 refresh hash와
  수명을 갖는 `AuthSession`을 저장한다. OAuth success handler가 refresh와 access cookie를 발급한다.
  migration `V15__create_auth_sessions.sql`이 이 저장 구조를 만든다.
- [`JwtProperties`](../src/main/resources/application.properties)는 access token 만료를
  `PT1H`, nickname token을 `PT10M`로 설정한다. `AuthCookieManager`에는 refresh cookie 발급이 있지만,
  닉네임 가입 완료 경로에서는 아직 access cookie만 발급한다.
- [`SecurityConfig`](../src/main/java/com/knot/backend/global/config/SecurityConfig.java)는
  `POST /api/v1/auth/logout`을 설정한다.
  [`JwtLogoutHandler`](../src/main/java/com/knot/backend/auth/presentation/handler/JwtLogoutHandler.java)는
  access·nickname cookie를 만료시킬 뿐 서버측 refresh family 저장·폐기는 하지 않는다.
- 신규 OAuth 사용자에게는 nickname token만 발급한다. Nickname completion은 `Member`와
  `OAuthIdentity`를 저장하고 access token을 발급하지만 현재 worktree에서는 refresh session을
  만들거나 refresh cookie를 발급하지 않는다.
- Nickname validation은 현재 `CompleteNicknameRequest`의 `@NotBlank`/`@Size(max=20)` 및
  `AuthenticatedMember`의 blank/length 검사다. 내부 공백과 허용 문자는 아직 검사하지 않아
  #429의 사용자가 확인한 규칙보다 구현 범위가 좁다.
- `AuthSession` 생성·저장과 기존 Member login의 refresh 발급은 보이지만 refresh endpoint의
  hash 조회·회전, replay 처리, session revoke는 아직 없다. `JwtLogoutHandler`도 access와
  nickname cookie만 만료한다. 따라서 session lifecycle의 일부 기반만 관찰됐으며 Issue 완료나
  refresh/logout 구현 완료로 기록하지 않는다.
- #427–#430은 열려 있다. #427은 existing login에서 access 1시간, refresh idle 7일·absolute
  30일과 신규 사용자의 10분 nickname token을 정한다. #428은 현재 family만 폐기하는 logout,
  #429는 domain nickname 문자 규칙과 가입 완료 시 session/cookie 발급,
  #430은 refresh rotation/reuse 처리의 별도 구현 범위를 둔다. OAuth candidate의 탈퇴 Member
  재로그인 제한은 이 Issue 묶음에서 확인되지 않아 미정으로 남긴다.

저장·동시성 선택과 남은 질문은 [Persona draft](../.persona/decisions/draft/refresh-token-session-lifecycle.md)에
제안 상태로 둔다. 도메인 정책으로 승격하려면 팀 확인이 필요하다.

## 남은 제품·기술 충돌

- 최신 도메인 규칙은 Document의 `DRAFT`, `DRAFTING`, `ARCHIVED` 상태가 없다고 기록하지만,
  기존 API 상세 초안과 2026-09-22 ERD에는 DRAFT 계열 상태가 남아 있다.
- 도메인 규칙의 생성 실패 자동 재시도와 ERD의 OWNER 수동 재시도 설명이 다르다.
- API 후보는 녹음 후 최종 오디오 파일 한 개를 올리는 흐름이고, ERD에는 chunk upload 구조가
  남아 있다.
- Workspace 생성 상한은 별도 `[BE] ... 생성 3개 제한` Issue #396이 2026-09-30
  `not planned`로 닫히며 무제한 생성으로 정리됐다. 9/30 Notion의 생성·참여 후보와 일치한다.
  Workspace 삭제·탈퇴의 현재 담당자 계약은 open Issue #410과 #386에 있으며, 9/30 Notion/API
  capture 및 이전 API·ERD에는 완전히 반영되지 않았다.
- 동시 녹음 범위, 생성 시점의 Document 확인 대상, 원문 segment 저장 형식도 서로 연관된
  제품 결정으로 남아 있다.

각 충돌은 현재 문서 사이의 불일치다. 최신 날짜, `최종`이라는 제목, API 후보의 시연 우선
표시만으로 대체 관계를 추정하지 않는다. 어느 문서가 팀의 현재 합의인지 확인한 뒤 제품 기준,
ERD, API 계약과 구현을 같은 방향으로 갱신한다.

### 2026-10-01 GitHub Issue 대조

- [#396](https://github.com/woowacourse-teams/2026-Knot/issues/396)은 `not planned`로
  닫혔다. 종료 의견은 직접 생성 상한을 없애고 기존 무제한 생성 동작을 유지한다고 기록한다.
- [#410](https://github.com/woowacourse-teams/2026-Knot/issues/410)은 open 상태다. OWNER의
  Workspace soft-delete와 진행·일시정지 녹음 폐기, 이미 저장된 오디오·전사·문서 보존을 다룬다.
- [#386](https://github.com/woowacourse-teams/2026-Knot/issues/386)은 open 상태다. 10/1
  담당자 결정은 일반 탈퇴·OWNER 승계 후 탈퇴 시 본인의 진행·일시정지 녹음만 폐기하고 다른
  멤버 세션과 저장된 자료는 보존한다고 적는다. 상태 전달 경로와 연결 복구는 이 Issue의 작업 범위다.
- [#384](https://github.com/woowacourse-teams/2026-Knot/issues/384)와
  [#385](https://github.com/woowacourse-teams/2026-Knot/issues/385)은 open 상태로 30초
  heartbeat, 2분 무응답 만료, 일시정지를 제외한 누적 2시간 상한을 다룬다. 후보와 Issue는
  기획·구현 대조 대상이며 완료된 구현은 아니다.
- 9/30 `/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/events` 후보의 SSE는
  승인 여부가 확인되지 않았다. #386은 HTTP 경로와 상태 전달 계약을 정하라고만 한다.
  다른 미확정 API는 사용자가 제품 결정을 내리기 전까지 제안 상태로 둔다.

## 구현 및 문서 갱신 규칙

1. 구현 전에 운영 Notion의 페이지 분류, 원문 상태, 변경 시각과 pending revision을 다시
   확인한다. 오래된 `자바의 Notion` 복사본은 운영 근거로 쓰지 않는다.
2. 해당 범위의 Controller, request/response DTO, validation, Security, persistence,
   migration, 테스트와 개발 OpenAPI를 조사해 현재 동작을 기록한다.
3. 초안이 승인되기 전에는 API spec, Persona Harness backlog와 Issue를 계약 변경으로
   갱신하거나 endpoint 구현을 시작하지 않는다. 독립적으로 이미 확정된 작업만 진행한다.
4. 사용자 판단이 필요한 충돌은 Notion 문장, 저장소 현재 내용, 구현 상태, 영향과 선택지를
   한 묶음으로 제시한다. 팀 승인이 필요한 결정은 사용자 개인 판단과 구분한다.
5. 합의가 확인되면 제품 기준, API 명세, 도메인 규칙·ERD, backend harness와 연결된 Issue를
   함께 갱신한다. 기존 기록은 삭제하지 않고 저장소의 `history` 규칙에 따라 보존한다.

`frontend/` 변경은 별도의 범위와 요청이 있기 전까지 수행하지 않는다.
