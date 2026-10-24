# Nginx·Spring 요청 ID로 API 단건 로그 연결

- 구현 이슈: [#672](https://github.com/woowacourse-teams/2026-knot/issues/672), 담당자 `jyt6640`
- 결정 기록: 저장소 루트 `backend/docs/adr/672-request-id-log-correlation-first.md` (Proposed)
- 범위: HTTP 요청별 로그 연결. OpenTelemetry·Tempo·DB 큐 업무 추적은 후속 단계다.
- 현재 검증은 로컬 실행이다. 이 문서는 Dev/Prod에 이미 적용됐다는 기록이 아니다.

## 문제 상황

메트릭으로 오류율이나 응답 지연은 알 수 있지만, 특정 요청의 Nginx 접근 로그와 Spring 오류 로그를 같은 키로 찾기 어려웠다.
여러 서버가 처리하므로 발생 시각만 대조하면 다른 요청을 혼동할 수 있다.
검색 본문의 `requestId`는 중복 실행 방지용 UUID이며 모든 HTTP 요청의 식별자가 아니다.

## 대안

| 방법 | 얻는 것 | 비용·한계 |
| --- | --- | --- |
| 시각·경로로 기존 로그 대조 | 설정 추가 없이 조사 가능 | 동시 요청·여러 서버·재연결을 구분하기 어렵다 |
| 요청 ID + MDC | 개별 HTTP 요청의 접근·오류 로그 연결 | SQL·외부 호출의 세부 시간 구간은 제공하지 않는다 |
| Java Agent + Tempo | HTTP·DB·외부 호출 span과 지연 구간 | 수집기·저장소·샘플링·자원 검증 필요. DB 큐로 넘긴 작업은 별도 연결 설계가 필요하다 |

## 결정

요청 ID 로그 연결을 먼저 구현하고, 추후 Dev에서 Agent·Tempo를 검증한다.
이번 단계에 필요한 필터·MDC·TaskDecorator는 기존 Spring 의존성으로 구현하며 새 라이브러리나 DB 필드는 추가하지 않는다.

```text
클라이언트 → Nginx: 새 $request_id 생성 (외부 X-Request-ID 덮어쓰기)
                 → Spring: X-Request-ID 검증 → MDC requestId → 로그 request_id
클라이언트 ← 응답 X-Request-ID ← Nginx: Spring의 동일 헤더 숨기고 단일 값 반환
```

- ID: 정확히 32자리 소문자 hex. Spring 직접 접근 시 없거나 잘못됐거나 헤더가 여러 개면 새 값을 생성한다.
- 요청 attribute로 최초 ID를 저장하여 ASYNC·ERROR 재진입에서도 같은 ID를 사용한다.
- Security보다 앞선 필터에서 응답 헤더를 설정하므로 인증·CSRF 실패도 추적할 수 있다.
- MDC는 스레드별이다. 종료·예외 시 이 필터가 소유한 `requestId`만 복원하고 다른 MDC 키를 삭제하지 않는다.
- `searchStreamExecutor`는 제출 시점의 ID를 전달하고 실행 뒤 작업 스레드의 이전 값을 복원한다.
- DB 큐를 거친 검색 생성 Worker는 원래 HTTP 요청과 자동 연결되지 않는다. `answerId`·`jobId`·Span Link는 이번 범위가 아니다.
- 로그에는 ID·서버 식별·HTTP method/status/dispatch를 추가한다. 필터는 질문·본문·쿠키·Authorization·쿼리 문자열을 새로 기록하지 않는다.
- Spring의 `HTTP dispatch finished`는 해당 디스패치 종료 기록이다. 예외 처리 전 상태나 SSE 초기 디스패치의 200을 최종 성공으로 해석하지 않는다. 최종 HTTP 상태·시간은 Nginx 접근 로그와 후속 ERROR/ASYNC를 함께 본다.
- SSE 응답의 첫·재연결 요청은 각각 별도 HTTP ID다. 연결 시간은 AI 생성 시간이나 첫 토큰 지연이 아니다.

## 확인: 서버 적용

이 저장소에는 실제 Dev/Prod API virtual host 전체가 없다. 아래는 적용 예시이며 현재 서버의 `nginx -T`와 함께 검토해야 한다.
Grafana/수집용 virtual host 대신 **API proxy location**에 적용한다.

1. 해당 API 서버의 virtual host와 `nginx -T` 결과를 비공개 위치에 백업한다.
2. `ops/observability/nginx/access-log.conf`는 기존처럼 `http` scope에 한 번 포함한다.
3. 응답 snippet은 API `server`에, proxy snippet은 실제 `proxy_pass`와 같은 location에 포함한다.

```nginx
http {
    include /etc/nginx/snippets/knot-access-log.conf;
    server {
        # 기존 listen/TLS/server_name 설정을 유지한다.
        include /etc/nginx/snippets/knot-request-id-response.conf;
        location / {
            # 기존 proxy_pass·Host·X-Forwarded-*·SSE timeout/buffering 설정을 유지한다.
            proxy_pass http://127.0.0.1:8080;
            include /etc/nginx/snippets/knot-request-id-proxy.conf;
        }
    }
}
```

snippet 파일명은 서버에 복사할 때 정한 이름이며 저장소의 `request-id-response.conf`·`request-id-proxy.conf`와 대응한다.
location에 다른 `add_header`가 있으면 server의 `add_header`를 상속하지 않을 수 있으므로 응답 snippet도 그 location에 포함한다.
`proxy_set_header` 역시 상속 규칙이 있으므로 기존 Host·X-Forwarded-* 등을 실수로 잃지 않도록 같은 scope에 둔다.
기존 `access_log` override가 있는 location은 JSON 파일로 실제 기록되는지 따로 확인한다.

4. Spring 서비스 환경에 안정적인 `KNOT_INSTANCE` 값(예: `knot-dev-1`)을 설정한다. 없으면 `HOSTNAME`, 둘 다 없으면 `unknown`이다.
5. 먼저 Spring을 배포하고 fallback ID를 확인한 뒤 Nginx snippet을 적용한다. Nginx 변경은 `nginx -t` 성공 후 reload한다.
6. 직접 Spring 포트는 외부에 열지 않는다. 형식 검증은 발급자의 인증이 아니며, 신뢰 경계는 외부 입력을 덮어쓰는 Nginx와 네트워크 접근 제한이다.
7. Dev 확인 후 Prod에 같은 절차로 적용한다. Prod가 다른 런타임이면 Spring 변경이 적용됐다고 간주하지 않는다.

Nginx JSON의 추가 필드는 `request_id`, `node`, `upstream_addr`, `upstream_status`다.
기존 `time/host/method/uri/status/duration/upstream_duration`과 로그 파일 경로는 유지한다.
Alloy는 현재 원문을 Loki로 전달하므로 설정을 바꾸지 않는다. 요청마다 다른 ID는 **Loki stream label로 만들지 않는다**.

## 확인: 실제 요청 조회

```sh
curl -sS -D - -o /dev/null https://dev-api.knoted.kr/actuator/health
```

응답의 `X-Request-ID`를 복사한 뒤 Grafana Explore에서 `Knot Loki`를 선택한다.
오류 발생 시각을 포함하도록 시간 범위를 좁히고 아래 `<ID>`를 실제 32자리 값으로 바꾼다.

```logql
{env="dev", job="nginx"} | json | request_id="<ID>"
```

```logql
{env="dev", job="spring"} |= "request_id=<ID> "
```

Prod는 `env="prod"`로 바꾼다. 실제 stream의 job/service는 현재 Alloy 설정과 Explore 라벨 목록을 대조한다.
Nginx에서만 ID가 보이고 status/upstream_status가 502라면 Spring에 도달하지 못했을 수 있으므로 upstream 주소·서비스 상태를 먼저 확인한다.
Spring ERROR가 보이면 동일 ID 주변의 dispatch 로그와 안전하게 요약된 원인을 확인한다.
새 ID가 없는 과거 로그는 계속 시각·경로로 조사해야 한다.

## 검증 결과

2026-10-14 로컬 검증:

- 필터: ID 생성·형식/중복 검증, 예외 정리, 다른 MDC 보존, ASYNC/ERROR·중첩 ERROR 재사용, 동시 스레드 격리 통과.
- TaskDecorator: 제출 시점 값 전달, 작업 스레드의 기존 값 복원, ID 없는 작업·예외 정리 통과.
- 기존 Security MVC: 정상·401·403·500 응답 ID, 500 안전한 오류 로그의 MDC ID와 기존 오류 응답 계약 통과.
- 기존 실제 SSE 작업: production 설정의 Executor로 첫 질문·재연결 작업의 MDC ID, 오류 async dispatch·오류 로그 ID·이미 전송한 본문·응답 ID 보존 통과.
- 실제 로컬 Nginx 1.28 + 전체 Spring 애플리케이션 + PostgreSQL 컨테이너: 정상 응답/두 로그 연결, 외부 중복 ID 덮어쓰기, 다음 요청 새 ID, 401/403, upstream 502, Spring 직접 fallback·CORS 노출 4건 통과.
- 실제 Dev/Prod 배포·Loki 수신·사용자가 보는 응답 헤더는 아직 관측하지 않았다.

```sh
./gradlew test --tests '*RequestId*Test' --tests '*SecurityConfigTest' --tests '*GlobalExceptionHandlerSecurityMvcTest' --tests '*SearchSseLoggingMvcTest'
./gradlew integrationTest --tests '*RequestIdNginxIntegrationTest'
./gradlew spotlessCheck check bootJar
node --test ops/observability/private-configuration.test.mjs
```

최종 `spotlessCheck check bootJar`는 통과했다. XML 기준 단위 1,425건·통합 649건·인수 561건,
총 2,635건의 실패·오류·건너뜀은 0건이다. 마지막 실행은 단위·통합·JAR의 이전 성공 결과를
Gradle up-to-date로 재사용했고 인수 561건을 다시 실행했다. 운영 설정 보존 테스트(Node)도 4건 통과했다.

검증 도중 기존 CORS 노출 헤더를 단일 `Retry-After`로 기대하던 인수 테스트 2건은
새 계약 `Retry-After, X-Request-ID`를 정확히 검증하도록 수정했다.
Ryuk 접속 실패와 PostgreSQL 초기 연결 EOF로 컨텍스트 시작이 실패한 실행도 있었다.
PostgreSQL 오류는 같은 코드의 해당 클래스 재실행과 전체 인수 재실행에서 통과했으며,
테스트를 건너뛰거나 타임아웃을 완화하지 않았다. 이 환경 오류를 요청 ID 기능 오류로 기록하지 않는다.

## 복구

- 헤더 중복/불일치: 응답 snippet 상속·Spring 헤더 숨김·다른 proxy의 ID 재발급 여부를 먼저 확인한다.
- Nginx 변경 문제: 백업한 해당 virtual host/snippet을 복원하고 `nginx -t` 성공 후 reload한다. 로그 파일·기존 수집 설정은 삭제하지 않는다.
- Spring 변경 문제: 이전 정상 JAR로 돌아간다. Nginx가 ID를 발급해도 이전 Spring 로그에는 해당 ID가 없을 수 있다.
- ID 연결 오류 때문에 인증·CSRF 정책을 완화하거나 DB의 검색 멱등성 ID를 바꾸지 않는다.

## 참고

- [Nginx $request_id](https://nginx.org/en/docs/http/ngx_http_core_module.html#var_request_id)
- [Nginx proxy_set_header](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header)
- [Nginx add_header 상속·always](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header)
- [Spring OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html)
