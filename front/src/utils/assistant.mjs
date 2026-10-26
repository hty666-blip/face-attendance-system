import {newRequestKey, requireData} from './attendance.mjs'

export const RUN_STATES = {RUNNING: '处理中', COMPLETED: '已完成', NEEDS_INPUT: '需要补充', FAILED: '处理失败', TIMED_OUT: '处理超时', INTERRUPTED: '已中断'}
export const ASSISTANT_TOOLS = {
  query_attendance_summary: {label: '查询月度汇总', source: '考勤报表中心 · 统一月报口径'},
  explain_attendance_day: {label: '解释本人单日结果', source: '考勤工作台 · 每日依据'},
  create_attendance_export: {label: '创建月报导出任务', source: '考勤报表中心 · 本人导出任务'},
  get_export_status: {label: '查询导出任务状态', source: '考勤报表中心 · 本人导出任务'}
}

export function assistantPayload(conversationId, message, requestKey) {
  if (!Number.isSafeInteger(Number(conversationId)) || Number(conversationId) <= 0) throw new Error('请先选择或新建会话。')
  if (typeof message !== 'string' || !message.trim() || message.trim().length > 2000) throw new Error('请输入 1–2000 字的问题。')
  if (typeof requestKey !== 'string' || !/^[\w-]{16,64}$/.test(requestKey)) throw new Error('请求编号无效，请刷新后重试。')
  return Object.freeze({conversationId: Number(conversationId), requestKey, message: message.trim()})
}

export function assistantPersistence(storage, userId) {
  const key = `attendance-assistant-pending:${Number(userId)}`
  return {
    load: () => {const value = storage.getItem(key); return value ? JSON.parse(value) : null},
    save: value => storage.setItem(key, JSON.stringify(value)),
    clear: () => storage.removeItem(key)
  }
}

// 未知结果仅恢复原请求。手动恢复先查本人回执，确实不存在才重发原三字段。
export class AssistantSubmission {
  constructor(api, keyFactory = newRequestKey, persistence = null) {
    this.api = api; this.keyFactory = keyFactory; this.persistence = persistence
    this.phase = 'idle'; this.payload = null; this.result = null; this.error = ''; this.active = true
    try {
      const saved = persistence?.load()
      if (saved) {this.payload = assistantPayload(saved.conversationId, saved.message, saved.requestKey); this.phase = 'uncertain'; this.error = '上次问题的结果尚未确认，请先恢复原请求。'}
    } catch { /* 无效存储不作为已完成的业务证据。 */ }
  }
  get locked() {return ['submitting', 'uncertain', 'running'].includes(this.phase)}
  dispose() {this.active = false}
  accept(result) {
    if (!result?.id || !Object.hasOwn(RUN_STATES, result.state)) throw new Error('运行响应不完整。')
    if (!this.active) return false
    this.result = result; this.error = ''; this.phase = result.state === 'RUNNING' ? 'running' : 'settled'
    if (this.phase === 'settled') {try {this.persistence?.clear()} catch { /* 已确认回执，下次恢复仍会查到同一运行。 */ }}
    return true
  }
  async submit(conversationId, message) {
    if (!this.active || this.locked) return false
    try {this.payload = assistantPayload(conversationId, message, this.keyFactory())}
    catch (error) {this.phase = 'failed'; this.error = error.message; return false}
    try {this.persistence?.save(this.payload)}
    catch {this.phase = 'failed'; this.error = '浏览器无法保存恢复信息，请允许会话存储后再发送。'; return false}
    return this.postOriginal()
  }
  async postOriginal() {
    if (!this.active) return false
    this.phase = 'submitting'; this.error = ''
    try {return this.accept(requireData(await this.api.post('/assistant/runs', this.payload, {timeout: 70000})))}
    catch (error) {
      if (!this.active) return false
      if (error.businessError && ['401', '403', '404', '409', '422'].includes(String(error.code))) {
        this.phase = 'failed'; this.error = error.message
        try {this.persistence?.clear()} catch { /* 明确拒绝，无需重放。 */ }
      } else {this.phase = 'uncertain'; this.error = '处理结果尚未确认，可能已执行业务工具。请恢复原请求，避免重复创建导出。'}
      return false
    }
  }
  async recover() {
    if (!this.active || !['uncertain', 'running'].includes(this.phase) || !this.payload) return false
    const previous = this.phase; this.phase = 'submitting'; this.error = ''
    try {
      const result = requireData(await this.api.get('/assistant/runs/by-request', {params: {requestKey: this.payload.requestKey}}))
      return this.accept(result)
    } catch (error) {
      if (!this.active) return false
      if (error.businessError && String(error.code) === '404') return this.postOriginal()
      this.phase = previous; this.error = '暂时无法核对原请求，请检查网络后再次恢复。原问题与编号已保留。'
      return false
    }
  }
}

export function toolLabel(toolName) {return ASSISTANT_TOOLS[toolName]?.label || '业务工具回执'}
export function toolSource(toolName) {return ASSISTANT_TOOLS[toolName]?.source || '服务端业务回执'}
export function receiptPresentation(tool) {
  const restricted = tool?.visibilityChanged === true || tool?.data?.visibilityChanged === true
  const available = !restricted && tool?.data !== null && typeof tool?.data === 'object'
  const succeeded = tool?.state === 'SUCCEEDED'
  return {
    restricted, available,
    label: restricted ? '历史范围已变化' : succeeded && !available ? '回执待核对' : succeeded ? '执行成功' : '执行失败',
    type: restricted || (succeeded && !available) ? 'warning' : succeeded ? 'success' : 'danger',
    source: restricted ? 'Java当前权限校验' : toolSource(tool?.toolName)
  }
}
export function toolArguments(args = {}) {
  const parts = []
  if (typeof args.month === 'string') parts.push(args.month)
  if (args.scope === 'SELF') parts.push('本人')
  if (args.scope === 'DEPARTMENT') parts.push(`部门 #${args.deptId}`)
  if (typeof args.date === 'string') parts.push(args.date)
  if (args.jobId !== undefined) parts.push(`任务 #${args.jobId}`)
  return parts.join(' · ') || '按本轮已校验条件执行'
}

export function receiptJson(data) {return JSON.stringify(data ?? {}, null, 2)}
export function sortedRuns(runs = []) {
  return [...runs].sort((a, b) => {
    if (Number.isSafeInteger(a.turnIndex) && a.turnIndex > 0 && Number.isSafeInteger(b.turnIndex) && b.turnIndex > 0) return a.turnIndex - b.turnIndex
    // 旧回执没有轮次编号时沿用时间，并列保持服务端原顺序，不用随机UUID猜时序。
    return String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
  }).slice(-20)
}
