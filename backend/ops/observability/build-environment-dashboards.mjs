import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = new URL('./', import.meta.url);
const sources = { overview: 'operations', api: 'operations', logs: 'operations', infra: 'infra', spring: 'spring', pools: 'pools', rds: 'rds' };
const kinds = {
  dev: ['overview', 'api', 'logs', 'infra', 'spring', 'pools', 'rds'],
  prod: ['overview', 'api', 'logs', 'infra', 'spring', 'pools', 'rds'],
};
const names = {
  overview: '전체 상태', api: 'API 요청·오류', logs: '로그', infra: '서버 자원',
  spring: 'Spring 앱', pools: 'Tomcat·DB 연결 풀', rds: 'RDS',
};
const dashboardName = (env, kind) => names[kind];
const descriptions = {
  '외부 API 정상 여부': ['지금 health가 응답하나?', '최근 외부 health 검사 결과입니다. 정상은 모든 API 기능이나 지난 기간 전체의 무장애를 보장하지 않습니다. 실패하면 로그 화면과 앱 상태를 확인하세요.'],
  '외부 API 응답 시간': ['health 확인에 얼마나 걸렸나?', '외부 health 확인 전체 시간입니다. DNS·연결·응답을 포함하며 실제 업무 API 지연과 다릅니다. 봉우리가 나온 시간의 로그와 서버 자원을 확인하세요.'],
  '호스트 지표 수집 상태': ['서버 지표가 들어오나?', '최근 2분 안에 CPU 지표가 들어왔는지 확인합니다. 수집 중은 EC2 전원 상태나 앱 정상 여부가 아닙니다. 없으면 Alloy와 전송 경로를 확인하세요.'],
  'CPU 사용률': ['CPU 사용률', 'AWS 앱 서버 CPU의 2분 평균 사용률입니다. 높은 값이 지속되면 서버 자원과 같은 시각의 요청·앱 로그를 확인하세요. NCP Grafana 컨테이너의 CPU가 아닙니다.'],
  '메모리 사용률': ['메모리 사용률', 'AWS 앱 서버에서 가용 메모리를 제외한 사용 비율입니다. 장기 상승이면 앱별 메모리와 GC를 확인하세요. NCP Grafana 컨테이너 메모리와는 다릅니다.'],
  '루트 디스크 사용률': ['서버 디스크 사용률', 'AWS 앱 서버 루트 디스크 사용률입니다. 앱 파일·로그·Docker 이미지가 포함되며 RDS 저장 공간이 아닙니다. 증가하면 서버에서 용량을 차지하는 경로를 확인하세요.'],
  '전체 API 요청률': ['초당 API 요청 수', 'Nginx 요청 로그에서 최근 5분의 초당 요청 수를 계산합니다. health·actuator 요청을 제외합니다. 실제 사용자·시나리오 트래픽이 없으면 값이 비어 있을 수 있습니다.'],
  '전체 API 4xx 비율': ['API 4xx 비율', '최근 5분 실제 API 요청 중 4xx 비율입니다. 인증·권한·없는 경로 등을 로그에서 확인하세요. 4xx만으로 서버 장애라고 판단하지 않습니다. 요청이 없으면 비율을 계산하지 않습니다.'],
  '전체 API 5xx 비율': ['API 5xx 비율', '최근 5분 실제 API 요청 중 5xx 비율입니다. 해당 시각의 Nginx 5xx와 앱 로그를 확인하세요. 요청이 없는 구간을 정상 0으로 대체하지 않습니다.'],
  '전체 API p50 응답 시간': ['API 응답 시간 p50', '최근 5분 Nginx 실제 API 요청의 중앙값입니다. health·actuator를 제외하며 요청이 없으면 계산하지 않습니다. 느린 요청의 URI와 앱 로그를 함께 확인하세요.'],
  '전체 API p95 응답 시간': ['느린 API 응답 시간 p95', '최근 5분 Nginx 실제 API 요청의 95백분위입니다. 약 95% 요청이 이 시간 이내에 끝났다는 뜻입니다. 요청이 적으면 쉽게 변하므로 요청 수와 로그를 함께 봅니다.'],
  '전체 API p99 응답 시간': ['느린 API 응답 시간 p99', '최근 5분 Nginx 실제 API 요청의 99백분위입니다. 소수의 느린 요청에 민감합니다. health·actuator를 제외하며 요청이 없는 구간은 계산하지 않습니다.'],
};

function pinExpression(expression, env) {
  const db = env === 'dev' ? 'knot-dev-database' : 'knot-database';
  return expression
    .replaceAll('env=~"$env"', `env="${env}"`)
    .replaceAll(',instance=~"$instance"', '')
    .replaceAll('instance=~"$instance",', '')
    .replaceAll('dimension_DBInstanceIdentifier=~"$db"', `dimension_DBInstanceIdentifier="${db}"`);
}

function decorate(panel, env) {
  const clone = structuredClone(panel);
  for (const target of clone.targets ?? []) target.expr = pinExpression(target.expr, env);
  if (clone.type === 'text') return clone;
  const detail = descriptions[clone.title];
  if (detail) [clone.title, clone.description] = detail;
  else if (clone.datasource?.uid === 'knot-prometheus' && clone.targets?.some(target => target.expr.includes('aws_rds_'))) {
    clone.description = `RDS 자체의 CloudWatch ${env === 'prod' ? '1분' : '5분'} 평균입니다.`;
    clone.description += ' 실시간 순간값이나 앱의 Hikari 연결 수가 아닙니다. 해당 DB 식별자의 로그·이벤트와 함께 확인하세요.';
  } else {
    clone.description = `${clone.description ?? `${env.toUpperCase()}의 ${clone.title} 지표입니다.`} 같은 시간대의 요청·앱 로그와 함께 확인하세요. 데이터 없음은 정상 0이 아닙니다.`;
  }
  return clone;
}

function intro(env, kind) {
  const environment = env.toUpperCase();
  const paragraphs = {
    overview: `${environment}만 표시합니다. **health 상태 → 지표 수집 → 자원 사용률** 순서로 보세요. API·로그·서버 상세는 위 링크로 이동합니다.\n\n색이 아닌 제목과 수치를 읽으세요. 데이터가 없으면 정상 0이 아닙니다.`,
    api: `${environment} 실제 API 요청을 Nginx 로그로 분석합니다. **요청 수 → 4xx/5xx → 느린 응답 → 경로별 오류** 순서로 보세요.\n\nhealth·actuator를 제외합니다. 요청이 없는 구간의 지연·비율은 계산하지 않습니다. 오류가 있으면 로그 화면에서 같은 시간을 확인하세요.`,
    logs: `${environment} 로그만 표시합니다. **앱 로그 → Nginx 요청 로그 → Nginx 5xx**를 따로 읽으세요. 시간 범위를 문제 발생 시각에 맞추고 위 Refresh로 갱신하세요.\n\n로그 없음만으로 수집 장애를 확정할 수 없습니다. 트래픽이 없거나 시간 범위가 짧을 수 있습니다. 전체 상태의 지표 수집과 Alloy 로그를 함께 확인하세요.`,
    infra: `${environment} AWS 앱 서버의 자원입니다. **CPU·메모리·디스크 추세**를 먼저 보고 필요할 때 아래 상세 지표를 확인하세요.\n\nNCP Grafana 서버 자원이나 EC2 전원 상태를 표시하는 화면은 아닙니다.`,
    spring: `${environment} Spring 앱 내부 지표입니다. **API → JVM 메모리 → GC → 스레드** 순서로 확인하세요.\n\n실제 요청이 없는 API 지연은 비어 있을 수 있습니다. 서버 전체 메모리는 서버 자원 화면에서 봅니다. 응답 시간 분포는 실제 Spring histogram bucket으로 표시합니다.`,
    pools: `${environment} 요청 처리와 DB 연결 풀입니다. **Tomcat busy → Hikari active/max → pending** 순서로 보세요.\n\npending은 연결을 기다리는 요청입니다. 오래 지속되면 앱 로그와 RDS를 함께 확인하세요. Hikari 연결 수는 RDS 전체 연결 수와 다릅니다.`,
    rds: env === 'dev'
      ? '**DEV RDS: `knot-dev-database`**의 CloudWatch 5분 평균입니다. CPU·연결 수·가용 메모리·스토리지를 확인하세요.\n\n순간값이 아니며 데이터가 없으면 정상 0으로 보지 않습니다. 앱 연결 풀은 Tomcat·DB 연결 풀 화면에서 봅니다.'
      : '**PROD RDS: `knot-database`**의 CloudWatch 1분 평균입니다. 현재 Spring 앱이 사용하는 DB입니다.\n\n앱의 Hikari 연결 풀과 RDS 전체 연결 수는 다릅니다. 순간값이 아니며 데이터가 없으면 정상 0으로 보지 않습니다.',
  };
  return { id: 1, type: 'text', title: `${environment} · 이 화면 읽는 법`, gridPos: { x: 0, y: 0, w: 24, h: kind === 'logs' || kind === 'rds' ? 4 : 3 }, options: { mode: 'markdown', content: paragraphs[kind] } };
}

function arrange(panels, startY) {
  let y = startY;
  for (let index = 0; index < panels.length; index += 2) {
    const row = panels.slice(index, index + 2);
    const height = Math.max(...row.map(panel => panel.gridPos.h));
    for (const [column, panel] of row.entries()) panel.gridPos = { x: column * 12, y, w: 12, h: height };
    y += height;
  }
}

function gauge(panel, threshold) {
  panel.type = 'gauge';
  panel.title = `지금 ${panel.title}`;
  panel.description += ' 현재 사용률이며 주의/위험 색은 운영 알림의 지속 시간 조건까지 만족했다는 뜻이 아닙니다. 추세는 서버 자원 화면에서 확인하세요.';
  Object.assign(panel.fieldConfig.defaults, { min: 0, max: 100, unit: 'percent', noValue: '확인 불가', thresholds: { mode: 'absolute', steps: [{ color: 'green', value: null }, { color: 'orange', value: threshold }, { color: 'red', value: 95 }] } });
  panel.options = { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false }, showThresholdLabels: true, showThresholdMarkers: true };
  for (const target of panel.targets) Object.assign(target, { instant: true, range: false });
  return panel;
}

function stateTimeline(health) {
  const timeline = structuredClone(health);
  timeline.id = 90;
  timeline.type = 'state-timeline';
  timeline.title = 'health 상태가 언제 바뀌었나?';
  timeline.description = '선택 기간의 외부 health 검사 성공/실패 이력입니다. 빈 구간은 확인 불가이며 성공으로 연결하지 않습니다. 앱 배포 기록이나 EC2 running/stopped 이력이 아닙니다.';
  timeline.fieldConfig.defaults.custom = { lineWidth: 0, fillOpacity: 70, spanNulls: false };
  timeline.options = { mergeValues: true, showValue: 'auto', alignValue: 'left', rowHeight: 0.9, legend: { displayMode: 'list', placement: 'bottom' }, tooltip: { mode: 'single' } };
  for (const target of timeline.targets) Object.assign(target, { instant: false, range: true });
  return timeline;
}

function overviewPanels(source, env) {
  const select = id => decorate(source.panels.find(panel => panel.id === id), env);
  const status = [select(2), select(7)];
  for (const [index, panel] of status.entries()) panel.gridPos = { x: index * 12, y: 3, w: 12, h: 5 };
  const resources = [gauge(select(4), 85), gauge(select(5), 90), gauge(select(6), 85)];
  for (const [index, panel] of resources.entries()) panel.gridPos = { x: index * 8, y: 8, w: 8, h: 6 };
  const timeline = stateTimeline(status[0]);
  timeline.gridPos = { x: 0, y: 14, w: 24, h: 5 };
  const duration = select(3);
  duration.gridPos = { x: 0, y: 19, w: 24, h: 7 };
  return [...status, ...resources, timeline, duration];
}

function errorTable(env) {
  return {
    id: 91, type: 'table', title: '어떤 경로에서 오류가 많았나?', datasource: { type: 'loki', uid: 'knot-loki' },
    description: '선택 기간 전체의 health·actuator 제외 4xx/5xx 요청을 URI와 상태 코드별로 비교합니다. 최근 5분 오류 비율과 범위가 다릅니다. 결과가 없으면 해당 오류가 없거나 로그가 없는 것이므로 로그 화면에서 구분하세요.',
    gridPos: { x: 0, y: 0, w: 24, h: 8 },
    targets: [{ refId: 'A', expr: `sum by (uri, status) (count_over_time({project="knot",job="nginx",env="${env}"} | json | uri !~ "/health|/actuator/.*" | status >= 400 | status < 600 | __error__="" [$__range]))`, queryType: 'instant' }],
    transformations: [
      { id: 'labelsToFields', options: { mode: 'columns' } },
      { id: 'organize', options: { excludeByName: { Time: true }, indexByName: { uri: 0, status: 1, Value: 2 }, renameByName: { uri: '요청 경로', status: 'HTTP 상태', Value: '오류 건수' } } },
      { id: 'sortBy', options: { sort: [{ field: '오류 건수', desc: true }] } },
    ],
    fieldConfig: { defaults: { unit: 'short', decimals: 0, noValue: '확인 불가' }, overrides: [] },
    options: { showHeader: true, cellHeight: 'sm' },
  };
}

function apiPanels(source, env) {
  const panels = [14, 15, 16, 17, 18, 19].map(id => decorate(source.panels.find(panel => panel.id === id), env));
  arrange(panels, 3);
  const table = errorTable(env);
  table.gridPos.y = 27;
  return [...panels, table];
}

function logPanels(source, env) {
  const template = source.panels.find(panel => panel.type === 'logs');
  const app = 'spring';
  const definitions = [
    ['앱 로그 · 예외와 처리 기록', `{project="knot",env="${env}",job="${app}"}`, `${env.toUpperCase()} Spring systemd unit의 journald 로그입니다. slot 레이블로 blue/green을 구분합니다.`],
    ['Nginx 요청 로그 · 경로와 응답', `{project="knot",env="${env}",job="nginx"} | json`, 'health 요청을 포함한 Nginx 원문입니다. uri, status, duration, upstream_duration을 확인하세요. 앱 내부 예외는 위 앱 로그에서 봅니다.'],
    ['Nginx 5xx · 실패한 요청만', `{project="knot",env="${env}",job="nginx"} | json | status >= 500 | status < 600 | __error__=""`, 'HTTP 500~599 요청만 표시합니다. 비어 있어도 전체 수집 정상의 증거는 아닙니다. 같은 시각의 앱 로그와 전체 요청을 함께 확인하세요.'],
  ];
  const logs = definitions.map(([title, expr, description], index) => ({
    ...structuredClone(template), id: index + 2, title, description: `${description} 시간과 레이블을 확인하고 필요한 구간으로 범위를 좁히세요.`,
    gridPos: { x: 0, y: 10 + index * 10, w: 24, h: 10 },
    targets: [{ refId: 'A', expr, queryType: 'range' }],
  }));
  const nginx = `{project="knot",env="${env}",job="nginx"}`;
  const count = `sum(count_over_time(${nginx} [$__range]))`;
  const coverage = {
    id: 90, type: 'stat', title: '로그 표본 · request_id 포함률',
    description: '선택 기간의 Nginx 로그 중 request_id가 있는 비율입니다. 요청 로그 자체가 없으면 표본 없음입니다. 앱 로그가 비어 있으면 요청 부재와 수집 실패를 추가로 구분해야 합니다.',
    datasource: { type: 'loki', uid: 'knot-loki' },
    gridPos: { x: 0, y: 4, w: 24, h: 6 },
    targets: [{ refId: 'A', queryType: 'instant', expr: `100 * (sum(count_over_time(${nginx} | json | request_id != "" | __error__="" [$__range])) or on() (0 * ${count})) / ${count}` }],
    fieldConfig: { defaults: { unit: 'percent', min: 0, max: 100, noValue: '표본 없음' }, overrides: [] },
    options: { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false } },
  };
  return [coverage, ...logs];
}

function heatmap(env, y) {
  return {
    id: 92, type: 'heatmap', title: 'Spring API 응답 시간 분포',
    description: '실제 Spring histogram bucket의 최근 5분 요청률입니다. 가로는 시간, 세로는 응답 시간 구간, 색의 진하기는 요청률입니다. health·actuator를 제외하고 요청이 없으면 빈 분포입니다. Nginx 지연과 측정 위치가 다릅니다.',
    datasource: { type: 'prometheus', uid: 'knot-prometheus' }, gridPos: { x: 0, y, w: 24, h: 9 },
    targets: [{ refId: 'A', expr: `sum by (le) (rate(http_server_requests_seconds_bucket{env="${env}",job="spring",uri!~"/health|/actuator/.*"}[5m]))`, format: 'heatmap', legendFormat: '{{le}}', instant: false, range: true }],
    options: { calculate: false, yAxis: { unit: 's' }, tooltip: { mode: 'single' }, legend: { show: true } },
    fieldConfig: { defaults: { noValue: '데이터 없음 / 요청 없음' }, overrides: [] },
  };
}

export async function buildEnvironmentDashboards() {
  const loaded = new Map();
  for (const name of new Set(Object.values(sources))) {
    loaded.set(name, JSON.parse(await readFile(new URL(`dashboards/knot-${name}.json`, root), 'utf8')));
  }
  const entries = [];
  for (const [env, environmentKinds] of Object.entries(kinds)) {
    for (const [index, kind] of environmentKinds.entries()) {
      const source = loaded.get(sources[kind]);
      const dashboard = structuredClone(source);
      dashboard.uid = `knot-${env}-${kind}`;
      dashboard.title = `${env.toUpperCase()} ${String(index + 1).padStart(2, '0')} ${dashboardName(env, kind)}`;
      dashboard.version = 1;
      dashboard.tags = ['knot', env, kind];
      dashboard.description = `${env.toUpperCase()} 전용 ${names[kind]}. 환경이 쿼리에 고정되어 URL 변수로 다른 환경을 섞지 않습니다.`;
      dashboard.templating = { list: [] };
      dashboard.time = { from: kind === 'logs' ? 'now-1h' : 'now-30m', to: 'now' };
      const help = intro(env, kind);
      let panels;
      if (kind === 'overview') panels = overviewPanels(source, env);
      else if (kind === 'api') panels = apiPanels(source, env);
      else if (kind === 'logs') panels = logPanels(source, env);
      else {
        panels = source.panels.filter(panel => panel.type !== 'text').map(panel => decorate(panel, env));
        arrange(panels, help.gridPos.h);
        if (kind === 'spring') panels.push(heatmap(env, Math.max(...panels.map(panel => panel.gridPos.y + panel.gridPos.h))));
      }
      dashboard.panels = [help, ...panels];
      dashboard.links = environmentKinds.map((destination, destinationIndex) => ({
        title: `${String(destinationIndex + 1).padStart(2, '0')} ${dashboardName(env, destination)}`,
        type: 'link', url: `/d/knot-${env}-${destination}`, keepTime: true, includeVars: false,
      }));
      const other = env === 'dev' ? 'prod' : 'dev';
      dashboard.links.push({ title: `${other.toUpperCase()}로 이동`, type: 'link', url: `/d/knot-${other}-overview`, keepTime: true, includeVars: false });
      entries.push({ env, kind, path: `provisioning/environment-dashboards/${env}/${dashboard.uid}.json`, dashboard });
    }
  }
  return entries;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  assert.equal(process.argv[2], '--emit-patch', 'Usage: node build-environment-dashboards.mjs --emit-patch DASHBOARD_UID');
  const entry = (await buildEnvironmentDashboards()).find(candidate => candidate.dashboard.uid === process.argv[3]);
  assert(entry, `Unknown dashboard UID: ${process.argv[3]}`);
  const content = JSON.stringify(entry.dashboard, null, 2) + '\n';
  const added = content.trimEnd().split('\n').map(line => '+' + line).join('\n');
  process.stdout.write(`*** Begin Patch\n*** Add File: ${fileURLToPath(new URL(entry.path, root))}\n${added}\n*** End Patch\n`);
}
