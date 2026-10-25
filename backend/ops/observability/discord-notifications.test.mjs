import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import test from 'node:test';

const root = new URL('./', import.meta.url);
const config = JSON.parse(await readFile(new URL('provisioning/alerting/knot.json', root), 'utf8'));

test('Discord 수신기는 한국어 제목과 본문 템플릿을 명시한다', () => {
  const receiver = config.contactPoints[0].receivers[0];
  assert.equal(receiver.settings.title, '{{ template "knot.discord.title" . }}');
  assert.equal(receiver.settings.message, '{{ template "knot.discord.message" . }}');
  assert.equal(receiver.settings.url, '$DISCORD_WEBHOOK_URL');
  assert.equal(receiver.disableResolveMessage, false);
});

test('알림 템플릿은 기술 라벨 덤프 대신 문제·확인 위치·환경별 링크를 사용한다', async () => {
  const provision = JSON.parse(await readFile(new URL('provisioning/alerting/discord-templates.json', root), 'utf8'));
  const template = provision.templates[0].template;
  assert(template.includes('define "knot.discord.title"'));
  assert(template.includes('define "knot.discord.message"'));
  for (const section of ['문제:', '감지:', '확인:', '시각:']) assert(template.includes(section));
  assert(template.includes('-overview'));
  assert(template.includes('-logs'));
  assert(template.includes('lt $index 2'));
  assert(template.includes('Asia/Seoul'));
  assert(!/SortedPairs|ValueString|\.Values|SilenceURL|PanelURL/.test(template));
  assert(template.includes('Updated'));
  assert(template.includes('복구 확인은 별도'));
  assert(template.includes('환경 확인 필요'));
  assert(template.includes('테스트'));
});

test('운영 규칙은 짧은 한국어 문제 이름을 제공한다', () => {
  const rules = config.groups.flatMap(group => group.rules);
  assert.equal(rules.length, 24);
  for (const rule of rules) {
    assert(rule.annotations.display_name.length > 3);
    assert(rule.annotations.display_name.length < 35);
  }
  const spring = rules.find(rule => rule.uid === 'knot-dev-spring');
  assert(spring.annotations.description.includes('API 중단을 확정하지 않습니다'));
});

test('2026-10-25 운영 24개 규칙의 조건·쿼리·대기·라우팅을 보존한다', () => {
  const core = {
    policies: config.policies,
    rules: config.groups.flatMap(group => group.rules.map(({ annotations, ...rule }) => rule)),
  };
  assert.equal(createHash('sha256').update(JSON.stringify(core)).digest('hex'),
    'a4dd4df8da9c098b505eb0526744395da680b8ce5582e0d4db79b55bfbb8b717');
});
