# 회의 문서 색인과 검색 검증

[#1087](https://github.com/woowacourse-teams/2026-knot/issues/1087)의 Java 구현은 PostgreSQL 저장 문서에서 워크스페이스별 색인을 만든다. Lucene의 한국어 분석기와 BM25, multilingual E5 임베딩의 코사인 벡터 검색을 수행하고 문서 순위를 RRF로 합친다. 실험의 384차원 E5와 접두어 계약을 사용한다. 로컬 파일 색인, 문자 단위 청크 분할, 한국어 분석기는 도입 후보 구현의 선택이다. [E5 모델 카드](https://huggingface.co/intfloat/multilingual-e5-small)와 [Lucene BM25 문서](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/search/similarities/Similarity.html)를 참고했다.

- DRAFT·ARCHIVED 저장 문서만 읽는다. DB의 전사 본문이나 문서가 없는 녹음은 색인에 입력하지 않는다.
- 색인 버전, 모델 리비전, 입력 내용이 같은 문서는 재시작 뒤에도 벡터를 재사용한다. 보존할 문서를 다시 조회했을 때 변경이 발견되거나 임베딩이 실패하면 진행하던 스냅샷을 공개하지 않는다.
- 요청의 현재 워크스페이스와 접수 시점을 기준으로 색인 후보를 제한한다. 명시 번호 목록의 문서 ID와 인식한 회의 날짜는 해당 항목에서만 적용한다. 순위 결과의 문서는 원본 조회 경로에서 다시 읽는다.
- 색인과 원본이 불일치하거나 색인·임베딩을 사용할 수 없으면 기존 검색기로 처리한다. 색인과 검색을 각각 끌 수 있다.

## 설정

| 설정 | 의미 |
| --- | --- |
| `knot.search.index.enabled` | 지정 워크스페이스의 초기·갱신 색인. 기본 비활성 |
| `knot.search.meeting-pipeline.enabled` | 회의 검색과 전문 생성의 공통 스위치. 기본 비활성 |
| `knot.search.index.query-enabled` | 지정 워크스페이스의 하이브리드 검색. 기본 비활성 |
| `knot.search.index.workspace-ids` | 색인할 워크스페이스 ID 목록 |
| `knot.search.index.directory` | 백엔드 인스턴스의 전용 비공개 색인 경로 |
| `knot.search.index.embedding-url` | E5 호환 임베딩 엔드포인트 전체 URL |
| `knot.search.index.embedding-model`, `embedding-revision` | 임베딩 모델 및 리비전 식별자 |
| `knot.search.index.embedding-token` | 필요한 경우 비밀 설정으로 제공 |

먼저 색인만 켜고 재처리, 삭제, 워크스페이스 분리와 임베딩 응답을 관찰한다. 현재 문서와 색인이 일치하는 인스턴스에서 검색을 비교한다. 검색만 롤백할 때 `query-enabled`를 해제한다. 검색과 전문 생성을 함께 기존 경로로 복귀할 때 공통 스위치를 해제한다. 색인 갱신까지 중지할 때 `knot.search.index.enabled`를 해제한다. 현재는 Spring 시작 시 적용하는 설정이다.

## 현재 검증 범위

파일 색인 재시작, 원본 변경·실패 후 재시도, 삭제와 무권한 후보 제외, KST 자정에서 항목별 범위 분리, 수량의 문서 ID 오인 방지, 기존 검색 회귀를 합성 자료로 점검했다. 임베딩 HTTP와 벡터는 대역이다. 실제 dev E5, 인스턴스별 비공개 볼륨, 재기동과 4·8 동시성, 자연어 복합 질문·회의 참조의 비교가 남아 있다.
