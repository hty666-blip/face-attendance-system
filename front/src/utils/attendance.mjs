export const ACTION_LABELS = {IN: '签到', OUT: '签退'}
export const SOURCE_LABELS = {FACE: '人脸打卡', CORRECTION: '批准补卡', LEGACY: '旧记录'}
export const REQUEST_STATUS = {PENDING: '待审批', APPROVED: '已批准', REJECTED: '已驳回', CANCELLED: '已撤回'}
export const HALF_LABELS = {AM: '上午', PM: '下午'}
const DAY_STATUS = {NORMAL: '正常', PRESENT: '正常', LATE: '迟到', EARLY: '早退', LATE_AND_EARLY: '迟到且早退', MISSING: '待核对 / 缺卡', MISSING_CARD: '待核对 / 缺卡', LEAVE: '已请假', ON_LEAVE: '已请假', IN_PROGRESS: '进行中', REST: '休息日', REST_DAY: '休息日', UPCOMING: '尚未到来', FUTURE: '尚未到来', RESTRICTED: '无权查看历史', LEGACY: '旧数据不推算', NOT_APPLICABLE: '未启用新口径'}

export function shanghaiDate(value = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date(value))
  return ['year', 'month', 'day'].map(type => parts.find(item => item.type === type).value).join('-')
}
export function pickerDate(value) {
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`
}
export function nextDate(value) {
  const [year, month, day] = value.split('-').map(Number)
  const result = new Date(Date.UTC(year, month - 1, day + 1))
  return `${result.getUTCFullYear()}-${String(result.getUTCMonth() + 1).padStart(2, '0')}-${String(result.getUTCDate()).padStart(2, '0')}`
}
export function localTimestamp(value) {
  if (!value) return '—'
  const text = String(value)
  if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$/.test(text)) return text.replace('T', ' ')
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return text
  return new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).format(date).replaceAll('/', '-')
}
export function dayLabel(day) {
  if (day.status === 'RESTRICTED') return DAY_STATUS.RESTRICTED
  if (day.missingIn || day.missingOut) return DAY_STATUS.MISSING
  return DAY_STATUS[day.status] || day.status || '待核对'
}
export function eventLabel(event) {
  if (!event) return '—'
  if (typeof event === 'string') return localTimestamp(event)
  return `${localTimestamp(event.occurredAt || event.createTime || event.eventTime)} · ${SOURCE_LABELS[event.source] || event.source || '原始事件'}`
}
export function requestRange(row) {
  if (row.kind === 'CORRECTION') return `${row.businessDate} ${ACTION_LABELS[row.action] || row.action || ''} · ${localTimestamp(row.correctionTime)}`
  return `${row.startDate || '—'} ${HALF_LABELS[row.startHalf] || ''} 至 ${row.endDate || '—'} ${HALF_LABELS[row.endHalf] || ''}`
}
export function newRequestKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  if (!globalThis.crypto?.getRandomValues) throw new Error('浏览器无法生成安全请求编号，请使用本机或 HTTPS 地址。')
  return [...globalThis.crypto.getRandomValues(new Uint8Array(20))].map(value => value.toString(16).padStart(2, '0')).join('')
}
export function requireData(response) {
  if (response?.code === undefined || response?.code === null) throw new Error('未收到有效的业务结果，请刷新核对。')
  if (String(response?.code) !== '200') {
    const conflict = String(response?.code) === '409'
    throw Object.assign(new Error(response?.msg || '操作未完成，请刷新后重试。'), {code: String(response?.code), conflict, businessError: true})
  }
  return response.data
}

// 网络结果未知时，只重试冻结的原申请；用户修改表单后不会意外创建第二份。
export class RequestSubmission {
  constructor(api, keyFactory = newRequestKey) {this.api = api; this.keyFactory = keyFactory; this.reset()}
  reset() {this.phase = 'idle'; this.payload = null; this.signature = null; this.error = ''; this.result = null}
  async submit(draft) {
    if (this.phase === 'submitting' || this.phase === 'succeeded') return false
    if (this.phase !== 'uncertain') {
      const signature = JSON.stringify(draft)
      if (!this.payload || signature !== this.signature) {this.payload = Object.freeze({...draft, requestKey: this.keyFactory()}); this.signature = signature}
    }
    this.phase = 'submitting'; this.error = ''
    try {
      const result = await this.api.post('/attendance/requests', this.payload)
      this.result = requireData(result); this.phase = 'succeeded'; return true
    } catch (error) {
      if (error.businessError) {this.phase = 'failed'; this.error = error.conflict ? `${error.message} 请先刷新申请列表核对状态。` : error.message}
      else {this.phase = 'uncertain'; this.error = '提交结果尚未确认，申请可能已经创建。请重试本次提交；也可以先查看申请列表。'}
      return false
    }
  }
}
