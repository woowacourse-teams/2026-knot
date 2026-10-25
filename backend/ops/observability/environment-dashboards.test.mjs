import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { buildEnvironmentDashboards } from './build-environment-dashboards.mjs';

const root = new URL('./', import.meta.url);
const entries = await buildEnvironmentDashboards();

test('Dev 7개와 Prod 7개를 서로 다른 UID로 생성한다', () => {
  assert.equal(entries.filter(entry => entry.env === 'dev').length, 7);
  assert.equal(entries.filter(entry => entry.env === 'prod').length, 7);
  assert.equal(new Set(entries.map(entry => entry.dashboard.uid)).size, 14);
});

test('선택 변수가 아닌 실제 쿼리로 환경과 RDS 식별자를 고정한다', () => {
  for (const { env, kind, dashboard } of entries) {
    assert.deepEqual(dashboard.templating.list, []);
    for (const panel of dashboard.panels) {
      for (const target of panel.targets ?? []) {
        assert(!/\$(?:env|instance|db)/.test(target.expr));
        const selectors = [...target.expr.matchAll(/\{([^{}]*)\}/g)].map(match => match[1]);
        assert(selectors.length > 0);
        for (const selector of selectors) assert(selector.includes(`env="${env}"`), target.expr);
        if (kind === 'rds') {
          const db = env === 'dev' ? 'knot-dev-database' : 'knot-database';
          assert(target.expr.includes(`dimension_DBInstanceIdentifier="${db}"`));
        }
      }
    }
  }
});

test('Prod Spring JVM과 연결 풀을 실제 prod selector로 조회한다', () => {
  for (const kind of ['spring', 'pools']) {
    const { dashboard } = entries.find(entry => entry.env === 'prod' && entry.kind === kind);
    assert(dashboard.panels.some(panel => panel.targets?.some(target => /jvm_|hikaricp_/.test(target.expr))));
  }
});

test('로그 화면은 앱 Nginx 5xx를 분리하며 빈 로그를 정상으로 대체하지 않는다', () => {
  for (const { env, dashboard } of entries.filter(entry => entry.kind === 'logs')) {
    const logs = dashboard.panels.filter(panel => panel.type === 'logs');
    assert.equal(logs.length, 3);
    assert(logs[0].targets[0].expr.includes('job="spring"'));
    assert(logs[1].targets[0].expr.includes('job="nginx"'));
    assert(logs[2].targets[0].expr.includes('status >= 500'));
    for (const panel of logs) {
      assert.equal(panel.gridPos.w, 24);
      assert.equal(panel.options.wrapLogMessage, true);
      assert(!/vector\(0\)|or.*0/.test(panel.targets[0].expr));
      assert(panel.description.length > 20);
    }
    assert(dashboard.panels[0].options.content.includes('수집 장애'));
  }
});

test('개요는 핵심 지표만 두고 모든 패널에 읽는 법을 제공한다', () => {
  for (const { dashboard } of entries.filter(entry => entry.kind === 'overview')) {
    assert.equal(dashboard.panels.length, 8);
    assert(dashboard.panels[0].options.content.length < 400);
    assert.equal(dashboard.panels.filter(panel => panel.type === 'stat').length, 2);
    assert.equal(dashboard.panels.filter(panel => panel.type === 'gauge').length, 3);
    assert.equal(dashboard.panels.filter(panel => panel.type === 'state-timeline').length, 1);
    assert(dashboard.links.some(link => link.url.endsWith('-logs')));
    for (const panel of dashboard.panels.slice(1)) assert(panel.description.length > 20);
  }
});

test('모든 링크의 대상이 존재하고 환경 변수는 전달하지 않는다', () => {
  const uids = new Set(entries.map(entry => entry.dashboard.uid));
  for (const { env, dashboard } of entries) {
    for (const link of dashboard.links) {
      const target = link.url.replace('/d/', '');
      assert(uids.has(target), link.url);
      assert.equal(link.includeVars, false);
      assert.equal(link.keepTime, true);
      if (!link.title.endsWith('로 이동')) assert(target.startsWith(`knot-${env}-`));
    }
  }
});

test('패널 ID와 gridPos가 중복되거나 겹치지 않는다', () => {
  for (const { dashboard } of entries) {
    assert.equal(new Set(dashboard.panels.map(panel => panel.id)).size, dashboard.panels.length);
    for (const [index, panel] of dashboard.panels.entries()) {
      const a = panel.gridPos;
      assert(a.x >= 0 && a.x + a.w <= 24 && a.y >= 0 && a.h > 0);
      for (const other of dashboard.panels.slice(index + 1)) {
        const b = other.gridPos;
        assert(!(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h));
      }
    }
  }
});

test('파일 프로비저닝은 기존 경로와 겹치지 않는 Dev Prod 폴더를 사용한다', async () => {
  const provider = await readFile(new URL('provisioning/dashboards/knot.yml', root), 'utf8');
  assert(provider.includes('folder: Knot-dev'));
  assert(provider.includes('folder: Knot-prod'));
  for (const env of ['dev', 'prod']) {
    assert(provider.includes(`path: /etc/grafana/provisioning/environment-dashboards/${env}`));
  }
  assert(provider.includes('path: /var/lib/grafana/dashboards'));
});

test('기존 알림의 대시보드와 패널 연결이 유지된다', async () => {
  const provision = JSON.parse(await readFile(new URL('provisioning/alerting/knot.json', root), 'utf8'));
  for (const group of provision.groups) {
    for (const rule of group.rules) {
      const uid = rule.annotations?.__dashboardUid__;
      if (!uid) continue;
      const original = entries.find(entry => entry.dashboard.uid === uid)?.dashboard
        ?? JSON.parse(await readFile(new URL(`dashboards/${uid}.json`, root), 'utf8'));
      assert(original.panels.some(panel => String(panel.id) === rule.annotations.__panelId__));
    }
  }
});

test('생성 파일이 원본과 생성 규칙의 결과와 일치한다', async () => {
  for (const { path, dashboard } of entries) {
    const installed = JSON.parse(await readFile(new URL(path, root), 'utf8'));
    assert.deepEqual(installed, dashboard, path);
  }
});

test('질문에 맞는 시각화와 최신값·기간값 조회를 구분한다', () => {
  for (const { dashboard } of entries.filter(entry => entry.kind === 'overview')) {
    for (const panel of dashboard.panels.filter(panel => panel.type === 'gauge')) {
      assert.equal(panel.fieldConfig.defaults.min, 0);
      assert.equal(panel.fieldConfig.defaults.max, 100);
      assert.equal(panel.fieldConfig.defaults.unit, 'percent');
      assert.equal(panel.targets[0].instant, true);
      assert.equal(panel.targets[0].range, false);
    }
    const timeline = dashboard.panels.find(panel => panel.type === 'state-timeline');
    assert.equal(timeline.targets[0].instant, false);
    assert.equal(timeline.targets[0].range, true);
    assert.equal(timeline.fieldConfig.defaults.custom.spanNulls, false);
  }
  for (const { dashboard } of entries.filter(entry => entry.kind === 'api')) {
    const table = dashboard.panels.find(panel => panel.type === 'table');
    assert.equal(table.targets[0].queryType, 'instant');
    assert(table.targets[0].expr.includes('[$__range]'));
    assert(table.targets[0].expr.includes('sum by (uri, status)'));
  }
  const heatmaps = entries.flatMap(entry => entry.dashboard.panels.filter(panel => panel.type === 'heatmap'));
  assert.equal(heatmaps.length, 2);
  assert(heatmaps[0].targets[0].expr.includes('http_server_requests_seconds_bucket{env="dev"'));
  assert.equal(heatmaps[0].targets[0].format, 'heatmap');
  assert.equal(heatmaps[0].options.calculate, false);
});

test('Prod는 현재 앱의 RDS와 1분 CloudWatch 수집을 설명한다', () => {
  const { dashboard } = entries.find(entry => entry.env === 'prod' && entry.kind === 'rds');
  assert(dashboard.panels[0].options.content.includes('현재 Spring 앱이 사용하는 DB'));
  assert(dashboard.panels[0].options.content.includes('1분'));
  assert.equal(dashboard.title, 'PROD 07 RDS');
});
