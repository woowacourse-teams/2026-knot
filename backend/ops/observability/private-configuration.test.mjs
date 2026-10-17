import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('./', import.meta.url);
const read = path => readFile(new URL(path, root), 'utf8');

test('수집 주소는 필수 환경변수이며 Prometheus와 Loki에 동일하게 적용한다', async () => {
  const compose = await read('agent/compose.yml');
  const alloy = await read('agent/config.alloy');
  assert(compose.includes('KNOT_INGEST_BASE_URL: ${KNOT_INGEST_BASE_URL:?'));
  assert.equal(alloy.match(/sys.env\("KNOT_INGEST_BASE_URL"\)/g).length, 2);
  assert(!/https:\/\/(?:\d{1,3}\.){3}\d{1,3}/.test(alloy));
  assert(!alloy.includes('insecure_skip_verify = true'));
});

test('수집 Nginx는 비공개 허용 목록·mTLS·환경별 인증과 POST 제한을 유지한다', async () => {
  const nginx = await read('nginx/ingest.conf');
  assert(nginx.includes('include /etc/nginx/snippets/knot-ingest-allow.conf;'));
  assert(nginx.includes('ssl_verify_client on;'));
  assert(nginx.includes('deny all;'));
  assert.equal(nginx.match(/limit_except POST \{ deny all; \}/g).length, 5);
  assert.equal(nginx.match(/CN=knot-dev-alloy/g).length, 2);
  assert.equal(nginx.match(/CN=knot-prod-alloy/g).length, 2);
  assert.equal(nginx.match(/CN=knot-mac-alloy/g).length, 1);
  assert(!/allow (?!127\.0\.0\.1;)(?:\d{1,3}\.){3}\d{1,3};/.test(nginx));
});

test('TLS SAN과 원 서버 점검 주소에 운영자의 비공개 입력을 요구한다', async () => {
  const certificate = await read('tls/server.ext');
  const script = await read('redeploy-dev-metrics.sh');
  assert(certificate.includes('subjectAltName=IP:$ENV::KNOT_INGEST_IP'));
  assert(script.includes('${KNOT_DEV_ORIGIN_URL:?'));
  assert(script.includes('${KNOT_DEV_ORIGIN_URL}/actuator/prometheus'));
  assert(!/http:\/\/(?!127\.0\.0\.1)(?:\d{1,3}\.){3}\d{1,3}/.test(script));
});

test('공용 Grafana는 auth proxy와 익명 접근을 끄고 안전한 쿠키로 로그인한다', async () => {
  const team = await read('compose.team.yml');
  assert(team.includes('GF_AUTH_PROXY_ENABLED: "false"'));
  assert(team.includes('GF_AUTH_ANONYMOUS_ENABLED: "false"'));
  assert(team.includes('GF_SECURITY_COOKIE_SECURE: "true"'));
  const nginx = await read('nginx/grafana-team.conf');
  assert(nginx.includes('proxy_set_header X-Knot-Viewer "";'));
});
