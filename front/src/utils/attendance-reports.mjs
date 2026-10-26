import {newRequestKey, requireData} from './attendance.mjs'

export const EXPORT_STATES = {QUEUED: '排队中', RUNNING: '生成中', READY: '可下载', FAILED: '生成失败', CANCELLED: '已取消', EXPIRED: '已过期'}
export const REPORT_METRICS = [
  ['peopleCount', '当前人员', '人'], ['workingDays', '预计工作日', '天'],
  ['elapsedWorkingDays', '已发生工作日', '天'], ['normalDays', '正常', '天'],
  ['lateDays', '迟到', '天'], ['earlyDays', '早退', '天'], ['missingDays', '待核对缺卡', '天'],
  ['leaveDays', '批准请假', '天'], ['lateMinutes', '迟到时长', '分钟'],
  ['earlyMinutes', '早退时长', '分钟'], ['restrictedDays', '历史不可见', '天']
]

export function reportQuery(draft) {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(draft?.month || '')) throw new Error('请选择有效的报表月份。')
  if (draft.scope === 'SELF') return {month: draft.month, scope: 'SELF'}
  if (draft.scope !== 'DEPARTMENT' || !Number.isSafeInteger(Number(draft.deptId)) || Number(draft.deptId) <= 0) throw new Error('请选择一个有权查看的部门。')
  return {month: draft.month, scope: 'DEPARTMENT', deptId: Number(draft.deptId)}
}

// 结果未知时锁定原条件与 requestKey。会话存储仅保存本人的报表条件，不含令牌。
export class ExportSubmission {
  constructor(api, keyFactory = newRequestKey, persistence = null) {
    this.api = api; this.keyFactory = keyFactory; this.persistence = persistence
    this.phase = 'idle'; this.payload = null; this.signature = null; this.error = ''; this.result = null
    try {
      const saved = persistence?.load()
      if (saved && typeof saved.requestKey === 'string' && /^[\w-]{16,64}$/.test(saved.requestKey)) {
        this.payload = Object.freeze({...reportQuery(saved), requestKey: saved.requestKey})
        this.signature = JSON.stringify(reportQuery(saved)); this.phase = 'uncertain'
        this.error = '上次创建结果尚未确认。已恢复原月份、范围和请求编号，请重试本次创建。'
      }
    } catch { /* 无效或不可用的会话存储不作为业务结果。 */ }
  }
  reset() {
    if (this.phase === 'submitting' || this.phase === 'uncertain') return false
    this.phase = 'idle'; this.payload = null; this.signature = null; this.error = ''; this.result = null
    return true
  }
  async submit(draft) {
    if (this.phase === 'submitting' || this.phase === 'succeeded') return false
    if (this.phase !== 'uncertain') {
      let query
      try {query = reportQuery(draft)} catch (error) {this.error = error.message; this.phase = 'failed'; return false}
      const signature = JSON.stringify(query)
      if (!this.payload || this.signature !== signature) {
        this.payload = Object.freeze({...query, requestKey: this.keyFactory()}); this.signature = signature
      }
    }
    this.phase = 'submitting'; this.error = ''
    try {this.persistence?.save(this.payload)} catch { /* 内存中的原请求仍可重试。 */ }
    try {
      const result = requireData(await this.api.post('/attendance/reports/exports', this.payload))
      if (!result?.id) throw new Error('导出任务响应缺少编号。')
      this.result = result; this.phase = 'succeeded'
      try {this.persistence?.clear()} catch { /* 业务成功已得到确认。 */ }
      return true
    } catch (error) {
      if (error.businessError) {
        this.phase = 'failed'; this.error = error.message
        try {this.persistence?.clear()} catch { /* 不影响明确业务结果。 */ }
      } else {
        this.phase = 'uncertain'
        this.error = '创建结果尚未确认，任务可能已经排队。请重试本次创建；刷新任务列表也能核对已有任务。'
      }
      return false
    }
  }
}

// blob 响应同样可能是 HTTP 200 的业务错误，必须解码校验后再触发文件保存。
export async function checkedReportBlob(blob) {
  if (!(blob instanceof Blob) || !blob.size) throw new Error('下载内容为空，请刷新任务后重试。')
  const bytes = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
  const zipped = bytes[0] === 0x50 && bytes[1] === 0x4b && bytes[2] === 0x03 && bytes[3] === 0x04
  if (zipped && !/json|text|html/i.test(blob.type)) return blob
  if (blob.size <= 65536) {
    const text = (await blob.text()).replace(/^\uFEFF/, '').trim()
    try {
      const body = JSON.parse(text)
      requireData(body)
    } catch (error) {if (error.businessError) throw error}
  }
  throw new Error('服务器未返回有效的 Excel 文件，请刷新任务状态后重试。')
}

export async function reportDownloadError(error) {
  if (error?.response?.data instanceof Blob) {
    try {await checkedReportBlob(error.response.data)} catch (reason) {return reason}
  }
  return error
}

export function exportScopeLabel(task, departments = []) {
  if (task.scope === 'SELF') return '本人'
  const found = departments.find(item => Number(item.id) === Number(task.deptId))
  return found?.name || `部门 #${task.deptId}`
}
