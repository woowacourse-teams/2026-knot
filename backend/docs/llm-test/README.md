# LLM API 품질·속도 실험

관련 이슈: #521 공통 LLM API 연동·주제 분류, #522 주제별 Markdown 생성.

현재 작성 구현 브랜치: `be/feature/#522` (#521 기반). 아래 초기 작성·선별 기록, #521 분류 관찰, #522 제품 요청 관찰을 구분한다.

## #522 구현 기본값과 실제 관찰

작성 규칙과 템플릿을 직접 전달하며 전체 원문과 주제 하나로 요청한다. 기본값은 추론 끔, temperature=0.2, top_p=0.9, top_k=20, repeat_penalty=1.0, max_tokens=4096, stream=false다. 예비 켬/512 요청도 추론 토큰 0이라 예산 적용은 확인하지 못했다.

- [#522 실제 작성 응답·실패 개선·품질 한계](522-document-generation-2026-10-08.md): 최종 형식 7개 통과, 짧은 의미 대조 6개 충족·장문 1개 부분 충족. 운영 최적값 확정을 뜻하지 않는다.
- [#522 구현 계획과 상위 실행기 연결 계약](../implement-plan/522-topic-markdown-generation.md)

작성 서비스는 application의 `DocumentGenerator` 계약을 통해 `LlmDocumentGenerator`를 호출한다. 아래 작성·선별 실험 10개는 #534에서 분리해 #535 범위에 보관한다. 예산 필드 정정은 과거 모델 관측을 새 검증으로 바꾸지 않는다.

## 이전 실험의 추천 후보

일반 작성은 **작성 프롬프트 v7 + 추론 512**, temperature=0.2, top_p=0.9, top_k=20, repeat_penalty=1.0, max_tokens=4096을 추천 후보로 선택했다. 서버 컨텍스트는 32768을 유지한다.

긴 합성 원문의 빠른 경로는 **발언 선별 v3 → 작성 v7**, 두 단계 모두 추론 끔이다. 같은 장문 한 주제에서 선별부터 문서 완성까지 3회 통과했고 29.26~37.80초였다. 여러 실제 장문에 검증된 운영 기본값으로 확대하지 않는다.

- [현재 추천값·요청에 넣을 설정](recommended-settings.md)
- [#521 전체 원문 주제 분류·합성 실제 API 10회](521-topic-classification-2026-10-08.md)
- [추가 HTTP 54회 비교·실제 출력·판정 근거](settings-comparison-2026-10-08.md)
- [작성용 시스템 프롬프트 v7 전체](topic-writer-v7.md)
- [발언 선별 시스템 프롬프트 v3 전체](topic-evidence-selector-v3.md)
- [장문 합성 입력 v2 전체](synthetic-long-v2.md)

## 이전 비교와 버전

- [초기 24회와 v4 8회·전체 프롬프트·실제 출력](writing-quality-2026-10-08.md)
- [SDK 추론 예산 8회와 HTTP 확인 1회](budget-tuning-2026-10-08.md)
- 작성 후보: [v4](topic-writer-v4.md), [v5](topic-writer-v5.md), [v6](topic-writer-v6.md).

## 기록 원칙과 제한

실제 서버 응답, 합성 입력, 설정, 판정 근거를 Markdown으로 남긴다. 인증값·실행 스크립트·개인 회의 원문·추론 내용은 포함하지 않는다. JSON 해석 성공과 문서 의미 정확성을 별도로 판정하고 실패한 결과도 보존한다.

모델 정보 API의 추론 지원 옵션은 off/on이다. low/medium/high를 실제 강도 단계라고 판단하지 않는다. top_p·top_k·repeat_penalty의 전체 조합이나 동시 처리량은 비교하지 않았다. 초기 실험만으로 #521·#522 완료를 판단하지 않으며, 각 구현의 자동 검사와 실제 공급자 관찰 문서를 별도로 확인한다.
