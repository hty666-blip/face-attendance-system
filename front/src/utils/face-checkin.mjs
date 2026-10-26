// 保留结果未知的原请求，重试时使用同一会话、照片和地址。
export class CheckInAttempt {
  constructor(api) {this.api = api; this.generation = 0; this.clear()}
  clear() {
    this.generation++; this.phase = 'idle'; this.error = ''; this.session = null
    this.expiresAt = null; this.attempt = null; this.action = null
  }
  async prepare(action) {
    if (this.attempt && ['uncertain', 'processing', 'verifying'].includes(this.phase)) return false
    this.clear()
    if (!['IN', 'OUT'].includes(action)) {this.phase = 'failed'; this.error = '请先选择服务端允许的签到或签退动作。'; return false}
    this.action = action
    const generation = this.generation
    this.phase = 'preparing'
    try {
      const result = await this.api.post('/face/challenge', {action})
      if (generation !== this.generation) return
      if (result.code !== '200') throw new Error(result.msg || '无法准备打卡，请重试。')
      this.session = result.data.challengeId; this.expiresAt = result.data.expiresAt
      this.phase = 'ready'
    } catch (reason) {
      if (generation !== this.generation) return
      this.phase = 'failed'; this.error = reason.message || '无法准备打卡，请检查网络后重试。'
    }
  }
  async capture(imageBase, address) {
    if (this.phase !== 'ready' || !this.session || !address) return false
    this.attempt = Object.freeze({challengeId: this.session, imageBase, address})
    return this.submit()
  }
  async submit() {
    if (!this.attempt || !['ready', 'uncertain', 'processing'].includes(this.phase)) return false
    const generation = this.generation
    const attempt = this.attempt
    this.phase = 'verifying'; this.error = ''
    try {
      const result = await this.api.post('/faceVerify',
        {challengeId: attempt.challengeId, imageBase: attempt.imageBase}, {params: {address: attempt.address}})
      if (generation !== this.generation) return false
      if (result.code === '200') {this.phase = 'succeeded'; this.attempt = null; return true}
      this.error = result.msg || '验证未完成，请重试。'
      if (result.code === '425') this.phase = 'processing'
      else {this.phase = 'failed'; this.attempt = null; this.session = null}
    } catch {
      if (generation !== this.generation) return false
      this.phase = 'uncertain'
      this.error = '尚未收到打卡结果，服务端可能已经成功。请重试本次验证，或关闭后先查看考勤记录。'
    }
    return false
  }
}
