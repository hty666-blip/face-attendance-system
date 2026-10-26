import {test} from 'node:test'
import assert from 'node:assert/strict'
import {CheckInAttempt} from '../src/utils/face-checkin.mjs'
import {dayLabel, eventLabel, localTimestamp, nextDate, requireData, RequestSubmission, shanghaiDate} from '../src/utils/attendance.mjs'

test('北京时间跨日与跨月使用指定时区，不受浏览器本机时区影响', () => {
  assert.equal(shanghaiDate('2026-10-25T17:00:00Z'), '2026-10-26')
  assert.equal(shanghaiDate('2026-12-31T16:01:00Z'), '2027-01-01')
  assert.equal(nextDate('2026-12-31'), '2027-01-01')
  assert.equal(nextDate('2028-02-28'), '2028-02-29')
  assert.equal(localTimestamp('2026-10-25 09:00:00'), '2026-10-25 09:00:00')
  assert.match(localTimestamp('2026-10-25T17:00:00Z'), /2026-10-26 01:00:00/)
})

test('受限历史不被缺卡标记覆盖，今日与旧记录不误称缺勤', () => {
  assert.equal(dayLabel({status: 'RESTRICTED', missingIn: true}), '无权查看历史')
  assert.equal(dayLabel({status: 'IN_PROGRESS'}), '进行中')
  assert.equal(dayLabel({status: 'LEGACY'}), '旧数据不推算')
  assert.equal(dayLabel({status: 'MISSING', missingOut: true}), '待核对 / 缺卡')
  assert.equal(dayLabel({status: 'LEAVE'}), '已请假')
})

test('补卡依据优先实际发生时间，保留来源', () => {
  assert.equal(eventLabel({occurredAt: '2026-10-25 09:00:00', createTime: '2026-10-26 12:00:00', source: 'CORRECTION'}), '2026-10-25 09:00:00 · 批准补卡')
})

test('业务冲突与网络错误分开，网络超时携带code也不能当明确失败', async () => {
  assert.throws(() => requireData({code: '409', msg: '版本已变化'}), error => error.businessError && error.conflict)
  assert.throws(() => requireData({data: {}}), error => !error.businessError)
  const calls = []; let count = 0
  const pending = new RequestSubmission({post: async (...args) => {calls.push(args); if (++count === 1) throw Object.assign(new Error('timeout'), {code: 'ECONNABORTED'}); return {code: '200', data: {id: 4}}}}, () => 'same-request-key')
  await pending.submit({kind: 'LEAVE', reason: 'original'}); assert.equal(pending.phase, 'uncertain')
  await pending.submit({kind: 'LEAVE', reason: 'changed'}); assert.equal(pending.phase, 'succeeded')
  assert.deepEqual(calls[0], calls[1]); assert.equal(calls[1][1].reason, 'original')
})

test('申请二次点击不并发，成功后不再次创建', async () => {
  let resolve, calls = 0
  const pending = new RequestSubmission({post: async () => {calls++; return new Promise(done => {resolve = done})}}, () => 'request-key')
  const first = pending.submit({kind: 'CORRECTION', reason: 'test'})
  assert.equal(await pending.submit({kind: 'CORRECTION', reason: 'test'}), false)
  assert.equal(calls, 1)
  resolve({code: '200', data: {id: 1}}); assert.equal(await first, true)
  assert.equal(await pending.submit({kind: 'CORRECTION', reason: 'test'}), false)
  assert.equal(calls, 1)
})

test('明确业务失败可编辑重提，仅内容变化才更换申请编号', async () => {
  const calls = []; let keys = 0
  const pending = new RequestSubmission({post: async (...args) => {calls.push(args); return {code: '422', msg: '日期不合法'}}}, () => `key-${++keys}`)
  await pending.submit({kind: 'LEAVE', reason: 'a'})
  assert.equal(pending.phase, 'failed')
  await pending.submit({kind: 'LEAVE', reason: 'a'})
  assert.equal(calls[0][1].requestKey, calls[1][1].requestKey)
  await pending.submit({kind: 'LEAVE', reason: 'b'})
  assert.notEqual(calls[1][1].requestKey, calls[2][1].requestKey)
})

test('扫脸会话明确绑定动作，结果未知时不能切换为新动作', async () => {
  const calls = []
  const pending = new CheckInAttempt({post: async (...args) => {calls.push(args); if (args[0] === '/face/challenge') return {code: '200', data: {challengeId: 'out-session'}}; throw new Error('timeout')}})
  await pending.prepare('OUT'); assert.deepEqual(calls[0], ['/face/challenge', {action: 'OUT'}])
  await pending.capture('private-image', 'original-address')
  assert.equal(pending.phase, 'uncertain')
  assert.equal(await pending.prepare('IN'), false)
  assert.equal(pending.action, 'OUT'); assert.equal(calls.length, 2)
  await pending.submit(); assert.deepEqual(calls[1], calls[2])
})

test('未选择或非法动作不创建会话', async () => {
  const pending = new CheckInAttempt({post: async () => {throw new Error('must not be called')}})
  assert.equal(await pending.prepare('OTHER'), false)
  assert.equal(pending.phase, 'failed')
  assert.match(pending.error, /选择/)
})
