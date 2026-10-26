<script setup>
import {computed, onMounted, onUnmounted, reactive, ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import request from '@/utils/request'
import {useUserStore} from '@/stores/user'
import {dayLabel, eventLabel, localTimestamp, requireData, shanghaiDate} from '@/utils/attendance.mjs'
import {checkedReportBlob, EXPORT_STATES, exportScopeLabel, ExportSubmission, reportDownloadError, REPORT_METRICS, reportQuery} from '@/utils/attendance-reports.mjs'

const userStore = useUserStore()
const canDepartment = computed(() => ['ADMIN', 'manager'].includes(userStore.getUser.role))
const canExport = computed(() => userStore.getAuths.includes('records.export'))
const draft = reactive({month: shanghaiDate().slice(0, 7), scope: 'SELF', deptId: null})
const departments = ref([]), departmentError = ref(''), departmentLoading = ref(false)
const preview = ref(null), previewLoading = ref(false), previewError = ref(''), previewTab = ref('people')
const peopleFilter = ref(null), pageNum = ref(1), pageSize = 10
const jobs = ref([]), total = ref(0), jobsLoading = ref(false), jobsError = ref('')
const detail = ref(null), detailLoading = ref(false), detailError = ref('')
const cancelling = ref(null), downloading = ref(null), operationError = ref('')
let mounted = true, previewGeneration = 0, jobsGeneration = 0, detailGeneration = 0
const persistence = {
  load: () => JSON.parse(sessionStorage.getItem(`attendance-report-pending:${userStore.getUserId}`) || 'null'),
  save: payload => sessionStorage.setItem(`attendance-report-pending:${userStore.getUserId}`, JSON.stringify(payload)),
  clear: () => sessionStorage.removeItem(`attendance-report-pending:${userStore.getUserId}`)
}
const creation = reactive(new ExportSubmission(request, undefined, persistence))
if (creation.phase === 'uncertain') Object.assign(draft, creation.payload)
const queryLocked = computed(() => ['submitting', 'uncertain'].includes(creation.phase))
const dailyRows = computed(() => (preview.value?.days || []).filter(item => peopleFilter.value === null || item.userId === peopleFilter.value))
const createLabel = computed(() => creation.phase === 'uncertain' ? '重试本次创建' : creation.phase === 'succeeded' ? '创建新版本' : '创建导出任务')
const scopeLabel = task => exportScopeLabel(task, departments.value)
const stateType = state => state === 'READY' ? 'success' : state === 'FAILED' ? 'danger' : ['QUEUED', 'RUNNING'].includes(state) ? 'warning' : 'info'
const isPending = state => ['QUEUED', 'RUNNING'].includes(state)

watch(() => [draft.month, draft.scope, draft.deptId], () => {
  ++previewGeneration; preview.value = null; previewError.value = ''; previewLoading.value = false; peopleFilter.value = null
})
const loadDepartments = async () => {
  if (!canDepartment.value) return
  departmentLoading.value = true; departmentError.value = ''
  try {
    const data = requireData(await request.get('/dept'))
    if (mounted) departments.value = Array.isArray(data) ? data.map(item => ({id: item.id, name: item.name})) : []
  } catch (error) {if (mounted) departmentError.value = error.message || '部门选项读取失败，请重试。'}
  finally {if (mounted) departmentLoading.value = false}
}
const loadPreview = async () => {
  if (queryLocked.value) return
  let query
  try {query = reportQuery(draft)} catch (error) {previewError.value = error.message; return}
  const current = ++previewGeneration; previewLoading.value = true; previewError.value = ''; preview.value = null
  try {
    const result = requireData(await request.get('/attendance/reports/preview', {params: query}))
    if (mounted && current === previewGeneration) {preview.value = result; peopleFilter.value = null}
  } catch (error) {if (mounted && current === previewGeneration) previewError.value = error.message || '报表预览读取失败，请检查网络后重试。'}
  finally {if (mounted && current === previewGeneration) previewLoading.value = false}
}
const loadJobs = async () => {
  const current = ++jobsGeneration; jobsLoading.value = true; jobsError.value = ''
  try {
    const result = requireData(await request.get('/attendance/reports/exports', {params: {pageNum: pageNum.value, pageSize}}))
    if (mounted && current === jobsGeneration) {jobs.value = result?.records || []; total.value = result?.total || 0}
  } catch (error) {if (mounted && current === jobsGeneration) jobsError.value = error.message || '任务列表读取失败，请重试刷新。'}
  finally {if (mounted && current === jobsGeneration) jobsLoading.value = false}
}
const createExport = async () => {
  if (!canExport.value || creation.phase === 'submitting') return
  if (creation.phase !== 'uncertain' && !preview.value) return
  if (creation.phase === 'succeeded') creation.reset()
  const result = await creation.submit(draft)
  if (mounted && result) {
    ElMessage.success('导出任务已创建，请刷新列表查看生成结果。')
    pageNum.value = 1; detail.value = creation.result; await loadJobs()
  }
}
const showDetail = async job => {
  const current = ++detailGeneration; detailLoading.value = true; detailError.value = ''; detail.value = null
  try {
    const result = requireData(await request.get(`/attendance/reports/exports/${job.id}`))
    if (mounted && current === detailGeneration) detail.value = result
  } catch (error) {if (mounted && current === detailGeneration) detailError.value = error.message || '任务详情读取失败，请刷新列表后重试。'}
  finally {if (mounted && current === detailGeneration) detailLoading.value = false}
}
const cancelExport = async job => {
  if (cancelling.value) return
  cancelling.value = job.id; operationError.value = ''
  try {
    const result = requireData(await request.post(`/attendance/reports/exports/${job.id}/cancel`))
    if (mounted) {if (detail.value?.id === job.id) detail.value = result; ElMessage.success('导出任务已取消。')}
  } catch (error) {if (mounted) operationError.value = error.businessError ? error.message : '取消结果尚未确认，请刷新任务列表；仍在排队或生成时可重试取消。'}
  finally {if (mounted) {cancelling.value = null; await loadJobs()}}
}
const download = async job => {
  if (downloading.value || !canExport.value) return
  downloading.value = job.id; operationError.value = ''
  try {
    const blob = await checkedReportBlob(await request.get(`/attendance/reports/exports/${job.id}/download`, {responseType: 'blob'}))
    if (!mounted) return
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = `考勤月报-${job.month}-${job.scope === 'SELF' ? '本人' : `部门${job.deptId}`}-任务${job.id}.xlsx`
    document.body.appendChild(link); link.click(); link.remove()
    // 浏览器接收下载后释放内存，不启动任务状态轮询。
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    const reason = await reportDownloadError(error)
    if (mounted) {
      operationError.value = reason.message || '下载失败，请刷新任务状态后重试。'
      if (reason.code === '401') userStore.logout()
    }
  } finally {if (mounted) downloading.value = null}
}
onMounted(async () => {await Promise.allSettled([loadDepartments(), loadJobs()]); if (!queryLocked.value && mounted) await loadPreview()})
onUnmounted(() => {mounted = false; ++previewGeneration; ++jobsGeneration; ++detailGeneration})
defineExpose({loadJobs})
</script>

<template>
  <section class="attendance-section report-workspace" aria-label="月度报表中心">
    <h2>月度报表中心</h2>
    <p class="attendance-note">按北京时间复核月度结果，导出人员汇总与每日依据。部门报表以当前在职员工档案为人员基准，历史日期继续按当时的可见范围过滤。</p>
    <el-form class="report-query" label-position="top" @submit.prevent="loadPreview">
      <el-form-item label="报表月份" label-for="report-month"><el-date-picker id="report-month" v-model="draft.month" type="month" value-format="YYYY-MM" format="YYYY年MM月" :clearable="false" :disabled="queryLocked" /></el-form-item>
      <el-form-item label="报表范围" label-for="report-scope"><el-select id="report-scope" v-model="draft.scope" :disabled="queryLocked"><el-option label="本人" value="SELF" /><el-option v-if="canDepartment" label="指定部门当前员工" value="DEPARTMENT" /></el-select></el-form-item>
      <el-form-item v-if="draft.scope === 'DEPARTMENT'" label="选择部门" label-for="report-department"><el-select id="report-department" v-model="draft.deptId" placeholder="请选择一个部门" :loading="departmentLoading" :disabled="queryLocked" no-data-text="没有可访问的部门"><el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
      <el-form-item class="report-query-action"><el-button type="primary" native-type="submit" :loading="previewLoading" :disabled="queryLocked">预览月报</el-button></el-form-item>
    </el-form>
    <div v-if="departmentError" class="attendance-actions"><span role="alert">{{ departmentError }}</span><el-button :loading="departmentLoading" @click="loadDepartments">重试读取部门</el-button></div>
    <p v-if="draft.scope === 'DEPARTMENT'" class="attendance-note">仅包含所选部门当前在职员工，最多 50 人。调出、停用人员不自动纳入，未知或其他部门的历史不计入结果。</p>
    <el-alert v-if="previewError" :title="previewError" type="error" show-icon :closable="false" class="attendance-error" />
    <p v-if="previewLoading" role="status">正在读取月报与逐日依据…</p>

    <div v-if="preview" class="report-preview">
      <div class="report-result-heading"><h3>{{ preview.month }} · {{ preview.scope === 'SELF' ? '本人月报' : `${preview.departmentName || scopeLabel(preview)}月报` }}</h3><p class="attendance-note">数据截至 {{ localTimestamp(preview.asOf) }} · 计算口径 {{ preview.calculationVersion }}</p></div>
      <el-alert v-if="preview.unsettled" title="该月含今天或未来日期，结果尚未结算。当前汇总用于考勤核对，不代表工资出勤。" type="warning" show-icon :closable="false" class="attendance-error" />
      <p v-else class="attendance-note">这是生成时可见的考勤结果，后续补卡或审批可能改变事实。需要更新文件时，请创建新版本。</p>
      <dl class="report-summary" aria-label="月报统计"><template v-for="[key,label,unit] in REPORT_METRICS" :key="key"><div><dt>{{ label }}</dt><dd>{{ preview.statistics?.[key] ?? '—' }} <span>{{ unit }}</span></dd></div></template></dl>
      <p class="attendance-note">部门统计中的“天”为逐人累计；历史不可见、旧数据不推算和未完成日期均保留标记，不折算为正常出勤。</p>
      <el-tabs v-model="previewTab" class="report-result-tabs">
        <el-tab-pane label="人员汇总" name="people">
          <div class="attendance-table"><el-table :data="preview.people || []" :max-height="480" stripe border empty-text="当前范围没有可纳入报表的在职员工。">
            <el-table-column label="员工" min-width="150"><template #default="{row}">{{ row.name || row.username || `员工 #${row.userId}` }}<span v-if="row.name && row.username" class="report-secondary"><br>{{ row.username }}</span></template></el-table-column>
            <el-table-column v-for="[key,label,unit] in REPORT_METRICS.filter(item => item[0] !== 'peopleCount')" :key="key" :label="`${label}（${unit}）`" :min-width="key === 'elapsedWorkingDays' || key === 'restrictedDays' ? 150 : 125"><template #default="{row}">{{ row.statistics?.[key] ?? '—' }}</template></el-table-column>
          </el-table></div>
        </el-tab-pane>
        <el-tab-pane label="每日依据" name="days">
          <div class="attendance-toolbar"><label for="report-person">筛选当前人员</label><el-select id="report-person" v-model="peopleFilter" clearable placeholder="全部当前人员"><el-option v-for="person in preview.people || []" :key="person.userId" :label="person.name || person.username || `员工 #${person.userId}`" :value="person.userId" /></el-select></div>
          <div class="attendance-table"><el-table :data="dailyRows" :max-height="480" stripe border empty-text="当前人员筛选下暂无每日结果。">
            <el-table-column prop="name" label="员工" min-width="125" />
            <el-table-column prop="businessDate" label="业务日期" width="116" />
            <el-table-column label="日结果" width="155"><template #default="{row}"><el-tag :type="row.missingIn || row.missingOut ? 'warning' : 'info'">{{ dayLabel(row) }}</el-tag></template></el-table-column>
            <el-table-column label="签到依据" min-width="210"><template #default="{row}"><span v-if="row.status === 'RESTRICTED'">历史范围不可见</span><span v-else>{{ eventLabel(row.checkIn) }}<strong v-if="row.missingIn"> · 缺签到</strong></span></template></el-table-column>
            <el-table-column label="签退依据" min-width="210"><template #default="{row}"><span v-if="row.status === 'RESTRICTED'">历史范围不可见</span><span v-else>{{ eventLabel(row.checkOut) }}<strong v-if="row.missingOut"> · 缺签退</strong></span></template></el-table-column>
            <el-table-column label="假期与异常" min-width="175"><template #default="{row}"><span v-if="!['RESTRICTED','LEGACY'].includes(row.status)">批准请假 {{ row.leaveDays ?? 0 }} 天<br>迟到 {{ row.lateMinutes ?? 0 }} 分 / 早退 {{ row.earlyMinutes ?? 0 }} 分<span v-if="row.pendingCount"><br>另有 {{ row.pendingCount }} 项待审批</span></span><span v-else>未计入出勤</span></template></el-table-column>
            <el-table-column label="规则依据" min-width="180"><template #default="{row}"><span v-if="row.status === 'LEGACY'">旧数据不推算</span><span v-else-if="row.status !== 'RESTRICTED'">{{ row.workingDay ? '工作日' : '休息日' }}<template v-if="row.policyId"><br>规则 #{{ row.policyId }} · {{ row.startTime }}–{{ row.endTime }}<br>宽限 {{ row.graceMinutes ?? 0 }} 分钟</template></span><span v-else>—</span></template></el-table-column>
          </el-table></div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <div class="report-export-action">
      <div><h3>导出 Excel</h3><p class="attendance-note">工作簿包含说明、人员汇总、每日明细。任务就绪后可在 24 小时内鉴权下载；文件保留生成时的结果。</p></div>
      <el-button v-if="canExport" type="primary" :loading="creation.phase === 'submitting'" :disabled="creation.phase !== 'uncertain' && !preview" @click="createExport">{{ createLabel }}</el-button>
      <p v-else class="attendance-note">当前账号可预览，没有导出权限。如需导出请联系管理员。</p>
    </div>
    <p v-if="!preview && creation.phase !== 'uncertain' && canExport" class="attendance-note">先选择月份与范围并预览月报，再创建导出任务。</p>
    <el-alert v-if="creation.error" :title="creation.error" :type="creation.phase === 'uncertain' ? 'warning' : 'error'" :closable="false" show-icon class="attendance-error" />
    <p v-if="creation.phase === 'uncertain'" class="attendance-note">本次条件已锁定：{{ creation.payload.month }} · {{ scopeLabel(creation.payload) }}。重试将使用同一请求编号，避免重复创建。</p>
    <p v-if="creation.phase === 'succeeded'" role="status" class="attendance-note">已确认任务 #{{ creation.result.id }}。再次点击“创建新版本”将明确创建另一份文件。</p>

    <div class="report-jobs-heading"><div><h3>我的导出任务</h3><p class="attendance-note">这里只显示本人创建的任务。生成中可手动刷新状态，页面不会持续轮询。</p></div><el-button :loading="jobsLoading" @click="loadJobs">刷新导出任务</el-button></div>
    <el-alert v-if="jobsError" :title="jobsError" type="error" :closable="false" show-icon class="attendance-error" />
    <el-alert v-if="operationError" :title="operationError" type="error" :closable="false" show-icon class="attendance-error" />
    <p v-if="jobsLoading" role="status">正在读取导出任务…</p>
    <div class="attendance-table"><el-table :data="jobs" :max-height="420" stripe border empty-text="还没有导出任务。预览月报后可创建第一份 Excel。">
      <el-table-column label="任务 / 月份" min-width="150"><template #default="{row}">#{{ row.id }}<br>{{ row.month }}</template></el-table-column>
      <el-table-column label="报表范围" min-width="145"><template #default="{row}">{{ scopeLabel(row) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="stateType(row.state)">{{ EXPORT_STATES[row.state] || row.state }}</el-tag></template></el-table-column>
      <el-table-column label="创建时间 / 下载期限" min-width="190"><template #default="{row}">{{ localTimestamp(row.createdAt) }}<template v-if="row.expiresAt"><br>有效至 {{ localTimestamp(row.expiresAt) }}</template></template></el-table-column>
      <el-table-column label="操作" min-width="235"><template #default="{row}"><div class="report-task-actions"><el-button link type="primary" @click="showDetail(row)">任务详情</el-button><el-button v-if="row.state === 'READY' && canExport" type="primary" plain :loading="downloading === row.id" :disabled="downloading !== null" @click="download(row)">下载 Excel</el-button><el-popconfirm v-if="isPending(row.state)" title="确认取消此导出任务？取消后可重新创建。" @confirm="cancelExport(row)"><template #reference><el-button :disabled="cancelling !== null" :loading="cancelling === row.id">取消任务</el-button></template></el-popconfirm></div></template></el-table-column>
    </el-table></div>
    <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev,pager,next,total" @current-change="loadJobs" />
    <p v-if="detailLoading" role="status">正在读取任务详情…</p>
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon class="attendance-error" />
    <section v-if="detail" class="report-task-detail" aria-label="导出任务详情"><div class="attendance-toolbar"><h4>任务 #{{ detail.id }} 详情</h4><el-button link type="primary" :loading="detailLoading" @click="showDetail(detail)">刷新此任务</el-button></div><dl class="attendance-facts"><dt>月份 / 范围</dt><dd>{{ detail.month }} / {{ scopeLabel(detail) }}</dd><dt>状态 / 生成尝试</dt><dd>{{ EXPORT_STATES[detail.state] || detail.state }} / {{ detail.attempts ?? 0 }} 次</dd><dt>创建时间</dt><dd>{{ localTimestamp(detail.createdAt) }}</dd><dt>开始 / 完成时间</dt><dd>{{ localTimestamp(detail.startedAt) }} / {{ localTimestamp(detail.finishedAt) }}</dd><dt>下载有效期</dt><dd>{{ localTimestamp(detail.expiresAt) }}</dd><dt v-if="detail.errorMessage">处理说明</dt><dd v-if="detail.errorMessage">{{ detail.errorMessage }}<span v-if="detail.errorCode">（{{ detail.errorCode }}）</span></dd></dl><p v-if="['FAILED','CANCELLED','EXPIRED'].includes(detail.state)" class="attendance-note">该任务已结束。确认当前月份与范围后，可预览并创建新版本。</p></section>
  </section>
</template>
