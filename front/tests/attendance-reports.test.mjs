import {test} from 'node:test'
import assert from 'node:assert/strict'
import {checkedReportBlob, ExportSubmission, exportScopeLabel, reportDownloadError, reportQuery} from '../src/utils/attendance-reports.mjs'

test('报表条件只发送月份与受限范围，本人范围不携带部门或其他用户', () => {
  assert.deepEqual(reportQuery({month: '2026-10', scope: 'SELF', deptId: 9, userId: 99, role: 'ADMIN'}), {month: '2026-10', scope: 'SELF'})
  assert.deepEqual(reportQuery({month: '2026-10', scope: 'DEPARTMENT', deptId: '3', ownerId: 99}), {month: '2026-10', scope: 'DEPARTMENT', deptId: 3})
  for (const draft of [{month: '2026-13', scope: 'SELF'}, {month: '2026-10', scope: 'ALL'}, {month: '2026-10', scope: 'DEPARTMENT'}, {month: '2026-10', scope: 'DEPARTMENT', deptId: 0}]) assert.throws(() => reportQuery(draft))
})

test('创建导出结果未知时锁定请求编号与原条件，不因表单变化创建第二份', async () => {
  const calls = []; let attempts = 0
  const creation = new ExportSubmission({post: async (...args) => {calls.push(args); if (++attempts === 1) throw new Error('timeout'); return {code: '200', data: {id: 12, state: 'QUEUED'}}}}, () => 'same-report-request-key')
  assert.equal(await creation.submit({month: '2026-10', scope: 'SELF'}), false)
  assert.equal(creation.phase, 'uncertain'); assert.equal(creation.reset(), false)
  assert.equal(await creation.submit({month: '2026-11', scope: 'DEPARTMENT', deptId: 3}), true)
  assert.deepEqual(calls[0], calls[1]); assert.equal(calls[0][0], '/attendance/reports/exports')
  assert.equal(calls[1][1].month, '2026-10'); assert.equal(calls[1][1].scope, 'SELF')
})

test('导出请求保存后可在页面恢复时继续原请求，确认结果才清除', async () => {
  let saved = null, clearCount = 0
  const persistence = {load: () => saved, save: value => {saved = value}, clear: () => {saved = null; clearCount++}}
  const first = new ExportSubmission({post: async () => {throw new Error('offline')}}, () => 'persist-report-request-key', persistence)
  await first.submit({month: '2026-10', scope: 'DEPARTMENT', deptId: 3})
  let payload
  const restored = new ExportSubmission({post: async (url, value) => {payload = value; return {code: '200', data: {id: 31}}}}, () => {throw new Error('must reuse old key')}, persistence)
  assert.equal(restored.phase, 'uncertain'); assert.equal(restored.payload.requestKey, 'persist-report-request-key')
  await restored.submit({month: '2026-11', scope: 'SELF'})
  assert.equal(payload.deptId, 3); assert.equal(payload.month, '2026-10'); assert.equal(clearCount, 1); assert.equal(saved, null)
})

test('双击导出不并发，成功后必须明确开启新版本', async () => {
  let resolve, key = 0, calls = 0
  const creation = new ExportSubmission({post: async () => {calls++; return new Promise(done => {resolve = done})}}, () => `report-request-key-${++key}`)
  const first = creation.submit({month: '2026-10', scope: 'SELF'})
  assert.equal(await creation.submit({month: '2026-10', scope: 'SELF'}), false)
  assert.equal(creation.reset(), false); assert.equal(calls, 1)
  resolve({code: '200', data: {id: 8}}); assert.equal(await first, true)
  assert.equal(await creation.submit({month: '2026-10', scope: 'SELF'}), false); assert.equal(calls, 1)
  assert.equal(creation.reset(), true); assert.equal(creation.phase, 'idle')
  const second = creation.submit({month: '2026-10', scope: 'SELF'})
  assert.equal(calls, 2); assert.equal(creation.payload.requestKey, 'report-request-key-2')
  resolve({code: '200', data: {id: 9}}); assert.equal(await second, true)
})

test('明确业务失败允许修改条件，相同条件重试沿用原编号', async () => {
  const calls = []; let key = 0
  const creation = new ExportSubmission({post: async (...args) => {calls.push(args); return {code: '409', msg: '人员范围已变化，请重新预览。'}}}, () => `report-request-key-${++key}`)
  await creation.submit({month: '2026-10', scope: 'SELF'}); assert.equal(creation.phase, 'failed')
  await creation.submit({month: '2026-10', scope: 'SELF'}); assert.equal(calls[0][1].requestKey, calls[1][1].requestKey)
  await creation.submit({month: '2026-11', scope: 'SELF'}); assert.notEqual(calls[1][1].requestKey, calls[2][1].requestKey)
})

test('无效响应不假装任务已创建，保留原编号待核对', async () => {
  const creation = new ExportSubmission({post: async () => ({code: '200', data: {state: 'QUEUED'}})}, () => 'report-response-unknown-key')
  await creation.submit({month: '2026-10', scope: 'SELF'})
  assert.equal(creation.phase, 'uncertain'); assert.equal(creation.payload.requestKey, 'report-response-unknown-key')
})

test('HTTP 200 的 JSON 下载业务错误必须解码，不保存成 Excel', async () => {
  const blob = new Blob([JSON.stringify({code: '403', msg: '部门范围已变化，请创建新版本。'})], {type: 'application/json'})
  await assert.rejects(() => checkedReportBlob(blob), error => error.businessError && error.code === '403' && /范围已变化/.test(error.message))
  const error = await reportDownloadError({response: {data: blob}})
  assert.equal(error.code, '403')
})

test('下载内容必须有 ZIP 文件头，拒绝空文件、HTML和伪成功 JSON', async () => {
  const file = new Blob([new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2])], {type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'})
  assert.equal(await checkedReportBlob(file), file)
  for (const blob of [new Blob([]), new Blob(['<html>login</html>'], {type: 'text/html'}), new Blob(['{"code":"200","data":{}}'], {type: 'application/json'}), new Blob(['PK\x03\x04bad'], {type: 'application/json'})]) await assert.rejects(() => checkedReportBlob(blob))
})

test('失效部门任务仍以编号可识别，不假定其历史部门权限', () => {
  assert.equal(exportScopeLabel({scope: 'SELF', deptId: 3}), '本人')
  assert.equal(exportScopeLabel({scope: 'DEPARTMENT', deptId: 3}, [{id: 3, name: '研发部'}]), '研发部')
  assert.equal(exportScopeLabel({scope: 'DEPARTMENT', deptId: 3}, []), '部门 #3')
})
