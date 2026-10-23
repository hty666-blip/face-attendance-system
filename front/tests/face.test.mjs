import {test} from 'node:test'
import assert from 'node:assert/strict'
import {CameraController} from '../src/utils/camera.mjs'
import {CheckInAttempt} from '../src/utils/face-checkin.mjs'

function cameraFixture(getMedia) {
  const state = [], track = {stopped: false, stop() {this.stopped = true}}
  const stream = {getTracks: () => [track]}
  const video = {videoWidth: 1920, videoHeight: 1080, pause() {}, play: async () => {}}
  const canvas = {getContext: () => ({drawImage() {}}), toDataURL: type => `data:${type};base64,sample`}
  const controller = new CameraController({getMedia: getMedia || (async () => stream), video, canvas, onState: value => state.push(value)})
  return {controller, track, stream, video, canvas, state}
}

test('关闭弹窗后才获准的摄像头流仍然释放', async () => {
  let resolve
  const fixture = cameraFixture(() => new Promise(done => {resolve = done}))
  const pending = fixture.controller.start()
  fixture.controller.stop(); resolve(fixture.stream); await pending
  assert.equal(fixture.track.stopped, true)
  assert.equal(fixture.video.srcObject, null)
  assert.equal(fixture.controller.ready, false)
})
test('正常拍摄保持比例，关闭释放设备并禁止继续拍摄', async () => {
  const {controller, canvas, track, state} = cameraFixture()
  assert.throws(() => controller.capture(), /尚未准备好/)
  await controller.start(); assert.equal(state.at(-1), 'ready')
  assert.match(controller.capture(), /^data:image\/jpeg/)
  assert.equal(canvas.width, 1280); assert.equal(canvas.height, 720)
  controller.stop(); assert.equal(track.stopped, true)
  assert.throws(() => controller.capture(), /尚未准备好/)
})
test('权限错误有可恢复提示，视频启动失败释放设备', async () => {
  const reason = Object.assign(new Error(), {name: 'NotAllowedError'})
  const {controller, state} = cameraFixture(async () => {throw reason})
  let message; controller.onState = (phase, detail) => {state.push(phase); message = detail}
  await controller.start(); assert.equal(state.at(-1), 'error'); assert.match(message, /权限被拒绝/)
  const fixture = cameraFixture(); fixture.video.play = async () => {throw new Error('failed')}
  await fixture.controller.start(); assert.equal(fixture.track.stopped, true)
})
test('连续开启只保留最新摄像头，旧流晚到不会覆盖', async () => {
  let first
  const fixture = cameraFixture()
  const secondStream = {getTracks: () => []}
  let calls = 0
  fixture.controller.getMedia = () => ++calls === 1 ? new Promise(done => {first = done}) : Promise.resolve(secondStream)
  const pending = fixture.controller.start(); await fixture.controller.start()
  first(fixture.stream); await pending
  assert.equal(fixture.track.stopped, true); assert.equal(fixture.video.srcObject, secondStream)
})

const ready = {code: '200', data: {challengeId: 'session-1', expiresAt: 'future'}}
test('未知结果重试复用原会话、照片和地址，重复点击不并发提交', async () => {
  const calls = []; let resolve
  const api = {post: async (...args) => {
    calls.push(args)
    if (args[0] === '/face/challenge') return ready
    if (calls.length === 2) throw new Error('timeout')
    return new Promise(done => {resolve = done})
  }}
  const attempt = new CheckInAttempt(api); await attempt.prepare()
  await attempt.capture('private-image', 'original-address'); assert.equal(attempt.phase, 'uncertain')
  const retry = attempt.submit(); assert.equal(attempt.phase, 'verifying')
  assert.equal(await attempt.submit(), false); assert.equal(calls.length, 3)
  assert.deepEqual(calls[1], calls[2])
  resolve({code: '200', data: {recordId: 10, alreadyCompleted: true}})
  assert.equal(await retry, true); assert.equal(attempt.attempt, null)
})
test('处理中保留请求，业务失败要求新会话', async () => {
  let response = {code: '425', msg: '处理中'}
  const attempt = new CheckInAttempt({post: async path => path === '/face/challenge' ? ready : response})
  await attempt.prepare(); await attempt.capture('sample', 'address')
  assert.equal(attempt.phase, 'processing'); assert.equal(attempt.attempt.imageBase, 'sample')
  response = {code: '422', msg: '未通过'}; await attempt.submit()
  assert.equal(attempt.phase, 'failed'); assert.equal(attempt.attempt, null); assert.equal(attempt.session, null)
  assert.equal(await attempt.submit(), false)
})
test('离开页面清除照片，晚到会话响应不能恢复状态', async () => {
  let resolve
  const attempt = new CheckInAttempt({post: () => new Promise(done => {resolve = done})})
  const pending = attempt.prepare(); attempt.clear(); resolve(ready); await pending
  assert.equal(attempt.phase, 'idle'); assert.equal(attempt.session, null); assert.equal(attempt.attempt, null)
})
