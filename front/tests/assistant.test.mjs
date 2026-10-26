import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {AssistantSubmission, assistantPayload, assistantPersistence, receiptPresentation, toolArguments, toolSource, sortedRuns} from '../src/utils/assistant.mjs'

const key = 'assistant-original-request-key'
const completed = (extra = {}) => ({id: 'run-a', conversationId: 7, message: '查询我本月汇总', state: 'COMPLETED', mode: 'MOCK', answer: '依据实际业务回执。', tools: [], ...extra})
const missing = () => ({code: '404', msg: '未找到本人的运行。'})
const store = () => {const data = new Map(); return {getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k)}}

test('助手请求只携带会话、原编号与问题，拒绝空白和越界输入', () => {
  assert.deepEqual(assistantPayload('7', ' 查询本人本月 ', key), {conversationId: 7, requestKey: key, message: '查询本人本月'})
  for (const args of [[0, '问题', key], [7, ' ', key], [7, '字'.repeat(2001), key], [7, '问题', 'short']]) assert.throws(() => assistantPayload(...args))
})

test('会话存储按登录用户隔离，不保存令牌或模型配置', () => {
  const storage = store(), first = assistantPersistence(storage, 11), second = assistantPersistence(storage, 12)
  first.save(assistantPayload(7, '本人本月', key))
  assert.equal(second.load(), null); assert.equal(first.load().requestKey, key)
  assert.deepEqual(Object.keys(first.load()), ['conversationId', 'requestKey', 'message'])
  second.clear(); assert.ok(first.load()); first.clear(); assert.equal(first.load(), null)
})

test('POST超时恢复先查原编号，已有运行时不再次调用工具', async () => {
  const calls = [], storage = store(), persistence = assistantPersistence(storage, 11)
  const first = new AssistantSubmission({post: async (...args) => {calls.push(args); throw new Error('timeout')}}, () => key, persistence)
  assert.equal(await first.submit(7, '查询我本月汇总'), false)
  assert.equal(first.phase, 'uncertain'); assert.equal(first.locked, true)
  assert.equal(calls[0][2].timeout, 70000)
  let gets = 0
  const restored = new AssistantSubmission({get: async (url, config) => {gets++; assert.equal(url, '/assistant/runs/by-request'); assert.equal(config.params.requestKey, key); return {code: '200', data: completed()}}, post: async () => {throw new Error('must not repost')}}, () => {throw new Error('must not generate a new key')}, persistence)
  assert.equal(restored.payload.message, '查询我本月汇总')
  assert.equal(await restored.recover(), true); assert.equal(gets, 1); assert.equal(restored.phase, 'settled'); assert.equal(persistence.load(), null)
})

test('原请求未接收时仅恢复原三字段，不接受新问题或新会话覆盖', async () => {
  let posted, saved = {conversationId: 7, requestKey: key, message: '原问题'}
  const submission = new AssistantSubmission({get: async () => missing(), post: async (url, payload) => {posted = payload; return {code: '200', data: completed()}}}, () => 'must-not-replace-request', {load: () => saved, clear: () => {saved = null}})
  assert.equal(await submission.submit(99, '替换问题'), false)
  assert.equal(await submission.recover(), true)
  assert.deepEqual(posted, {conversationId: 7, requestKey: key, message: '原问题'})
})

test('恢复查询遇到网络或权限错误不推断不存在，不重新POST', async () => {
  for (const get of [async () => {throw new Error('offline')}, async () => ({code: '403', msg: '当前无权限'})]) {
    let calls = 0
    const submission = new AssistantSubmission({get, post: async () => {calls++}}, () => key, {load: () => ({conversationId: 7, requestKey: key, message: '原问题'})})
    assert.equal(await submission.recover(), false); assert.equal(submission.phase, 'uncertain'); assert.equal(calls, 0)
  }
})

test('RUNNING手动确认后保持编号，终态才解除锁定与清除恢复信息', async () => {
  const persistence = assistantPersistence(store(), 11); let lookups = 0
  const submission = new AssistantSubmission({post: async () => ({code: '200', data: completed({state: 'RUNNING'})}), get: async () => ({code: '200', data: completed({state: ++lookups === 1 ? 'RUNNING' : 'INTERRUPTED', tools: [{sequence: 1, toolName: 'create_attendance_export', state: 'SUCCEEDED', data: {id: 3}}]})})}, () => key, persistence)
  assert.equal(await submission.submit(7, '导出本人本月'), true)
  assert.equal(submission.phase, 'running'); assert.ok(persistence.load())
  assert.equal(await submission.submit(7, '重复导出'), false)
  await submission.recover(); assert.equal(submission.phase, 'running'); assert.equal(submission.payload.requestKey, key)
  await submission.recover(); assert.equal(submission.phase, 'settled'); assert.equal(submission.result.tools[0].data.id, 3); assert.equal(persistence.load(), null)
})

test('发送和恢复均阻止并发，未得到回应不产生新编号', async () => {
  let done, calls = 0
  const submission = new AssistantSubmission({post: async () => {calls++; return new Promise(resolve => {done = resolve})}}, () => key)
  const sending = submission.submit(7, '查询本人本月')
  assert.equal(await submission.submit(7, '双击'), false); assert.equal(await submission.recover(), false); assert.equal(calls, 1)
  done({code: '200', data: completed()}); assert.equal(await sending, true)
})

test('卸载后不回写运行状态或清除持久恢复请求', async () => {
  let done
  const persistence = assistantPersistence(store(), 11)
  const submission = new AssistantSubmission({post: async () => new Promise(resolve => {done = resolve})}, () => key, persistence)
  const sending = submission.submit(7, '导出本人本月')
  submission.dispose(); done({code: '200', data: completed()})
  assert.equal(await sending, false); assert.equal(submission.result, null); assert.ok(persistence.load())
})

test('业务拒绝可恢复编辑，损坏的成功响应保留原请求待核对', async () => {
  const persistence = assistantPersistence(store(), 11)
  const denied = new AssistantSubmission({post: async () => ({code: '422', msg: '缺少月份'})}, () => key, persistence)
  await denied.submit(7, '导出'); assert.equal(denied.phase, 'failed'); assert.equal(denied.error, '缺少月份'); assert.equal(persistence.load(), null)
  const malformed = new AssistantSubmission({post: async () => ({code: '200', data: {state: 'COMPLETED'}})}, () => key, persistence)
  await malformed.submit(7, '导出'); assert.equal(malformed.phase, 'uncertain'); assert.equal(persistence.load().requestKey, key)
})

test('无法保存原请求时不执行可能创建导出的POST', async () => {
  let calls = 0
  const submission = new AssistantSubmission({post: async () => {calls++}}, () => key, {save: () => {throw new Error('storage blocked')}})
  assert.equal(await submission.submit(7, '导出'), false); assert.equal(calls, 0); assert.match(submission.error, /会话存储/)
})

test('来源由固定工具名映射，不把模型URL变成跳转或权威来源', () => {
  assert.equal(toolSource('create_attendance_export'), '考勤报表中心 · 本人导出任务')
  assert.equal(toolSource('https://attacker.test'), '服务端业务回执')
  assert.equal(toolArguments({month: '2026-10', scope: 'SELF', userId: 99, url: 'https://attacker.test'}), '2026-10 · 本人')
  const source = readFileSync(new URL('../src/components/attendance/AssistantPanel.vue', import.meta.url), 'utf8')
  assert.equal(/v-html|:href|window\.open|location\.href/.test(source), false)
  assert.match(source, /演示模式，非真实AI/); assert.match(source, /run\.answer/); assert.match(source, /open-reports/)
})

test('有界会话保持时间顺序，仅展示最近20轮', () => {
  const runs = Array.from({length: 25}, (_, i) => ({id: String(i), createdAt: `2026-10-26T${String(i).padStart(2, '0')}:00:00`}))
  const selected = sortedRuns(runs.reverse())
  assert.equal(selected.length, 20); assert.equal(selected[0].id, '5'); assert.equal(selected.at(-1).id, '24')
})

test('同秒会话按递增轮次排序，随机UUID反序不改变实际对话顺序', () => {
  const createdAt = '2026-10-26T22:00:00'
  const first = {id: 'zzzz-first', turnIndex: 1, createdAt}, second = {id: 'aaaa-second', turnIndex: 2, createdAt}
  assert.deepEqual(sortedRuns([second, first]).map(run => run.id), ['zzzz-first', 'aaaa-second'])
  const legacy = [{id: 'zzzz-legacy', createdAt}, {id: 'aaaa-legacy', createdAt}]
  assert.deepEqual(sortedRuns(legacy), legacy)
})

test('历史权限变化只展示范围变化说明，不将旧成功回执显示为当前成功', () => {
  for (const tool of [
    {toolName: 'create_attendance_export', state: 'SUCCEEDED', visibilityChanged: true, data: null},
    {toolName: 'query_attendance_summary', state: 'SUCCEEDED', data: {visibilityChanged: true, statistics: {normalDays: 99}, source: 'https://untrusted.test'}}
  ]) {
    assert.deepEqual(receiptPresentation(tool), {restricted: true, available: false, label: '历史范围已变化', type: 'warning', source: 'Java当前权限校验'})
  }
  const missing = receiptPresentation({toolName: 'create_attendance_export', state: 'SUCCEEDED', data: null})
  assert.equal(missing.available, false); assert.equal(missing.label, '回执待核对'); assert.equal(missing.type, 'warning')
  const normal = receiptPresentation({toolName: 'query_attendance_summary', state: 'SUCCEEDED', data: {statistics: {normalDays: 0}}})
  assert.equal(normal.available, true); assert.equal(normal.label, '执行成功')
})
