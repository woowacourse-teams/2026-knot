# 워크스페이스 초대는 검증용 keyed hash와 복원용 인증된 암호문을 함께 저장한다

## 상태

Superseded

- 원문 재조회용 암호문 저장은 ADR 399가 대체했다. 암호문 경로는 #444, 컬럼과 암호화 key는 #458에서 제거했다. HMAC lookup hash와 보안 난수는 유지한다.

## 관련 Issue

- #229 [BE] 워크스페이스 초대 발급·조회 API 구현

## 한 줄 요약

워크스페이스 초대는 검증용 keyed hash와 복원용 인증된 암호문을 함께 저장한다

## 왜 이 결정이 필요했나

워크스페이스 초대 참여는 원문을 저장하지 않는 lookup hash가 적합하지만, 초대 공유 화면은 새로고침 후에도 활성 code와 linkToken의 동일한 원문을 다시 표시해야 한다. 두 요구는 서로 다른 보안·조회 특성을 가진다.

PR #228은 V3에서 링크 토큰 해시와 초대 코드 해시만 저장하므로 서버가 활성 초대 원문을 복원할 수 없다. 매 새로고침마다 재발급하면 이미 공유한 초대를 예고 없이 무효화한다.

결정 동인:

- 활성 초대 원문의 안정적인 재조회
- 참여 API를 위한 keyed hash lookup 유지
- DB 단독 유출 시 원문 비노출
- 변조 감지와 트랜잭션 rollback
- 기존 V3 row와 PR #228 호환

## 트레이드 오프

- hash-only를 유지한다: DB 단독 유출 저항은 높지만 같은 활성 초대 원문을 재조회할 수 없다
- 인증된 암호문과 keyed hash를 함께 저장한다: 동일 원문 재조회와 lookup을 모두 지원하지만 key 관리와 동시 유출 위험이 추가된다

## 무엇을 결정했나

워크스페이스 초대는 검증용 keyed hash와 복원용 인증된 암호문을 함께 저장한다

AES-GCM과 별도 keyed hash를 병행하면 refresh 시 동일 값을 복원하면서도 참여 요청은 원문 대신 고정 길이 lookup hash로 찾을 수 있다. V4 nullable 컬럼으로 추가하면 PR #228의 V3 row를 수정하지 않고 호환할 수 있다.

## 결과

- 활성 초대 code와 linkToken을 멱등하게 다시 반환할 수 있다
- 암호문 변조를 인증 태그로 감지하고 원문·key를 로그와 오류에서 숨긴다
- lookup hash secret과 encryption key를 분리해 운영 환경에 주입해야 한다
- 기존 V3 row는 복원 불가능하므로 조회 시 정적 500 후 명시적 재발급이 필요하다
- DB와 key의 동시 유출 위험과 key rotation 부재가 남는다

## 다시 논의해야 할 조건

- 초대 key rotation 또는 KMS 연동이 필요해질 때
- 활성 초대 원문 재노출 요구가 제거될 때
- 보안 정책이 DB와 애플리케이션 key 동시 유출 위험을 허용하지 않을 때

## 확인

- 예정 경로: `backend/docs/adr/229-workspace-invitation-secret-recovery.md`
- 결정 주체: Knot 팀
- AI 하네스가 Proposed ADR 파일을 생성했다.
- 팀이 PR에서 승인한 뒤 Accepted로 바꾼다.
