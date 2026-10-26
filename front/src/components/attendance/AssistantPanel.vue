<script setup>
import {computed, onMounted, onUnmounted, reactive, ref} from 'vue'
import request from '@/utils/request'
import {useUserStore} from '@/stores/user'
import {requireData, localTimestamp} from '@/utils/attendance.mjs'
import {AssistantSubmission, assistantPersistence, RUN_STATES, toolLabel, receiptPresentation, toolArguments, receiptJson, sortedRuns} from '@/utils/assistant.mjs'
import {REPORT_METRICS, EXPORT_STATES} from '@/utils/attendance-reports.mjs'
import '@/assets/css/attendance-assistant.css'

const emit = defineEmits(['open-reports'])
const userStore = useUserStore(), ownerId = userStore.getUserId, status = ref(null), statusError = ref(''), statusLoading = ref(false)
const conversations = ref([]), total = ref(0), pageNum = ref(1), listLoading = ref(false), listError = ref('')
const selected = ref(null), runs = ref([]), detailLoading = ref(false), detailError = ref(''), creating = ref(false), message = ref('')
const sendError = ref(''), pageSize = 10
let mounted = true, listGeneration = 0, detailGeneration = 0
const persistence = {load: () => assistantPersistence(window.sessionStorage, ownerId).load(), save: value => assistantPersistence(window.sessionStorage, ownerId).save(value), clear: () => assistantPersistence(window.sessionStorage, ownerId).clear()}
const submission = reactive(new AssistantSubmission(request, undefined, persistence))
const locked = computed(() => submission.locked || creating.value)
const canSend = computed(() => status.value?.ready && selected.value && message.value.trim() && !locked.value && !detailLoading.value)
const latestRunning = computed(() => runs.value.findLast(run => run.state === 'RUNNING'))
const isMock = computed(() => status.value?.mode === 'MOCK')
const stateType = state => state === 'COMPLETED' ? 'success' : ['FAILED', 'TIMED_OUT', 'INTERRUPTED'].includes(state) ? 'danger' : state === 'RUNNING' ? 'warning' : 'info'
const isExport = tool => ['create_attendance_export', 'get_export_status'].includes(tool.toolName)
const metricRows = tool => REPORT_METRICS.filter(([key]) => tool.data?.statistics?.[key] !== undefined)

const loadStatus = async () => {
  statusLoading.value = true; statusError.value = ''
  try {const data = requireData(await request.get('/assistant/status')); if (mounted) status.value = data}
  catch (error) {if (mounted) statusError.value = error.message || '助手状态读取失败，请重试。'}
  finally {if (mounted) statusLoading.value = false}
}
const loadConversations = async () => {
  const generation = ++listGeneration; listLoading.value = true; listError.value = ''
  try {
    const data = requireData(await request.get('/assistant/conversations', {params: {pageNum: pageNum.value, pageSize}}))
    if (mounted && generation === listGeneration) {conversations.value = data?.records || []; total.value = data?.total || 0}
  } catch (error) {if (mounted && generation === listGeneration) listError.value = error.message || '会话列表读取失败，请重试。'}
  finally {if (mounted && generation === listGeneration) listLoading.value = false}
}
const selectConversation = async (id, force = false) => {
  if (locked.value && !force) return
  const generation = ++detailGeneration; detailLoading.value = true; detailError.value = ''; runs.value = []; selected.value = {id}
  try {
    const data = requireData(await request.get(`/assistant/conversations/${id}`))
    if (mounted && generation === detailGeneration) {selected.value = data.conversation; runs.value = sortedRuns(data.runs || [])}
  } catch (error) {if (mounted && generation === detailGeneration) detailError.value = error.message || '会话读取失败，请重试。'}
  finally {if (mounted && generation === detailGeneration) detailLoading.value = false}
}
const newConversation = async () => {
  if (locked.value || detailLoading.value) return
  ++detailGeneration; creating.value = true; sendError.value = ''
  try {
    const data = requireData(await request.post('/assistant/conversations', {}))
    if (mounted) {selected.value = data; runs.value = []; message.value = ''; detailError.value = ''; pageNum.value = 1; await loadConversations()}
  } catch (error) {if (mounted) sendError.value = error.message || '新建会话失败，请刷新会话列表核对。'}
  finally {if (mounted) creating.value = false}
}
const acceptSubmission = async accepted => {
  if (!mounted || !accepted) return
  const run = submission.result
  if (Number(run.conversationId) === Number(selected.value?.id)) runs.value = sortedRuns([...runs.value.filter(item => item.id !== run.id), run])
  if (run.state !== 'RUNNING') message.value = ''
  await loadConversations()
}
const send = async () => {
  if (!canSend.value || latestRunning.value) return
  sendError.value = ''; await acceptSubmission(await submission.submit(selected.value.id, message.value))
}
const recover = async () => {await acceptSubmission(await submission.recover())}
const refreshRun = async run => {
  if (submission.phase === 'submitting') return
  if (submission.locked) {await recover(); return}
  const generation = ++detailGeneration, conversationId = selected.value?.id
  detailLoading.value = true; detailError.value = ''
  try {
    const data = requireData(await request.get(`/assistant/runs/${run.id}`))
    if (mounted && generation === detailGeneration && conversationId === selected.value?.id) runs.value = sortedRuns([...runs.value.filter(item => item.id !== data.id), data])
  } catch (error) {if (mounted && generation === detailGeneration) detailError.value = error.message || '运行状态读取失败，请重试刷新。'}
  finally {if (mounted && generation === detailGeneration) detailLoading.value = false}
}
const useExample = value => {if (!locked.value && !latestRunning.value) message.value = value}
onMounted(async () => {
  await Promise.allSettled([loadStatus(), loadConversations()])
  if (!mounted) return
  if (submission.payload && submission.locked) {message.value = submission.payload.message; await selectConversation(submission.payload.conversationId, true)}
  else if (conversations.value.length) await selectConversation(conversations.value[0].id)
})
onUnmounted(() => {mounted = false; ++listGeneration; ++detailGeneration; submission.dispose()})
</script>

<template>
  <section class="attendance-section assistant-workspace" aria-label="AI考勤助手">
    <div class="assistant-heading"><div><h2>AI考勤助手</h2><p class="attendance-note">通过对话核对考勤、创建月报导出。查询与导出继续遵循当前账号权限。</p></div><el-button :loading="statusLoading" @click="loadStatus">刷新助手状态</el-button></div>
    <el-alert v-if="isMock" title="演示模式，非真实AI。规则解析问题，业务工具仍实际执行。" type="warning" show-icon :closable="false" class="attendance-error" />
    <el-alert v-if="status && !status.ready" :title="status.enabled ? 'AI助手尚未就绪' : 'AI助手未启用'" type="info" show-icon :closable="false" class="attendance-error"><p>{{ status.reason || '请由管理员配置聊天服务、模型与凭据后启用。' }}</p><p>历史对话仍可查看；考勤查询、申请和报表中心可继续使用。</p><el-button link type="primary" @click="emit('open-reports')">前往月度报表与导出</el-button></el-alert>
    <p v-else-if="status?.ready && status.mode === 'MODEL'" class="attendance-note">模型服务已就绪。模型可能需要补充条件；业务是否执行以每轮实际回执为准。</p>
    <el-alert v-if="statusError" :title="statusError" type="error" show-icon :closable="false" class="attendance-error" />
    <div class="assistant-layout">
      <aside class="assistant-conversations" aria-label="我的会话">
        <div class="assistant-list-heading"><h3>我的会话</h3><el-button type="primary" plain :loading="creating" :disabled="locked || detailLoading" @click="newConversation">新会话</el-button></div>
        <el-alert v-if="listError" :title="listError" type="error" :closable="false" class="attendance-error" />
        <p v-if="listLoading" role="status" class="attendance-note">正在读取会话…</p>
        <ul v-if="conversations.length" class="assistant-conversation-list"><li v-for="item in conversations" :key="item.id"><button type="button" :class="{'is-selected': Number(selected?.id) === Number(item.id)}" :aria-current="Number(selected?.id) === Number(item.id) ? 'true' : undefined" :disabled="locked" @click="selectConversation(item.id)"><span>{{ item.title || '考勤会话' }}</span><small>{{ localTimestamp(item.updatedAt || item.createdAt) }}</small></button></li></ul>
        <p v-else-if="!listLoading && !listError" class="attendance-note">还没有会话。新建后可提出第一个考勤问题。</p>
        <el-pagination v-if="total > pageSize" v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev,next,total" :disabled="locked" small @current-change="loadConversations" />
        <el-button class="assistant-refresh-list" :loading="listLoading" @click="loadConversations">刷新会话列表</el-button>
      </aside>
      <div class="assistant-dialogue">
        <div class="assistant-thread-heading"><h3>{{ selected?.title || (selected ? '考勤会话' : '开始一次考勤对话') }}</h3><p class="attendance-note">仅展示当前会话最近 20 轮。助手仅支持考勤查询和月报导出。</p></div>
        <p v-if="detailLoading" role="status">正在读取对话与业务回执…</p>
        <el-alert v-if="detailError" :title="detailError" type="error" show-icon :closable="false" class="attendance-error" />
        <el-button v-if="detailError && selected" :loading="detailLoading" @click="selectConversation(selected.id, true)">重试读取会话</el-button>
        <div v-if="!runs.length && !detailLoading" class="assistant-empty"><p>可以问：“查询我本月的考勤汇总”，或“解释我昨天的考勤”。</p><p class="attendance-note">需要文件时请明确月份和本人或部门范围。创建导出任务后，在报表中心查看生成状态并下载。</p></div>
        <ol v-if="runs.length" class="assistant-runs" aria-label="对话记录"><li v-for="run in runs" :key="run.id" class="assistant-run">
          <div class="assistant-question"><span class="assistant-speaker">我的问题</span><p>{{ run.message }}</p></div>
          <div class="assistant-answer"><div class="assistant-run-heading"><span class="assistant-speaker">{{ run.tools?.length ? '事实回执' : '助手说明' }}</span><div class="assistant-state"><el-tag v-if="run.mode === 'MOCK'" type="warning" size="small">演示模式</el-tag><el-tag :type="stateType(run.state)" size="small">{{ RUN_STATES[run.state] || '未知状态' }}</el-tag></div></div>
            <p v-if="run.answer" class="assistant-answer-text">{{ run.answer }}</p>
            <p v-if="run.state === 'RUNNING'" class="attendance-note">本轮尚在处理，请手动刷新确认。页面不会自动重发或持续轮询。</p>
            <p v-if="['FAILED','TIMED_OUT','INTERRUPTED'].includes(run.state)" class="attendance-note">本轮未完成全部处理。已显示的工具回执仍有效，请先核对已有导出任务，再发起新问题。<span v-if="run.errorCode">（{{ run.errorCode }}）</span></p>
            <div v-for="tool in run.tools || []" :key="tool.sequence" class="assistant-receipt"><div class="assistant-receipt-heading"><h4>{{ toolLabel(tool.toolName) }}</h4><el-tag :type="receiptPresentation(tool).type" size="small">{{ receiptPresentation(tool).label }}</el-tag></div><p v-if="!receiptPresentation(tool).restricted" class="attendance-note">{{ toolArguments(tool.arguments) }}</p><p class="assistant-source">来源：{{ receiptPresentation(tool).source }}<span v-if="receiptPresentation(tool).available && tool.data?.asOf"> · {{ localTimestamp(tool.data.asOf) }}</span></p>
              <p v-if="receiptPresentation(tool).restricted" class="attendance-note">历史可见范围已变化，请重新查询。原回执内容已隐藏。</p>
              <p v-else-if="tool.state === 'SUCCEEDED' && !receiptPresentation(tool).available" class="attendance-note">当前未返回可核对的业务数据，请刷新本轮状态或重新查询。</p>
              <template v-if="receiptPresentation(tool).available">
                <dl v-if="tool.state === 'SUCCEEDED' && metricRows(tool).length" class="assistant-metrics"><div v-for="[key,label,unit] in metricRows(tool)" :key="key"><dt>{{ label }}</dt><dd>{{ tool.data.statistics[key] }} {{ unit }}</dd></div></dl>
                <p v-if="tool.data?.unsettled" class="attendance-note">该月含未结算日期，结果用于考勤核对，不代表工资出勤。</p>
                <p v-if="tool.state === 'SUCCEEDED' && isExport(tool) && tool.data?.id" class="assistant-export-state">任务 #{{ tool.data.id }} · {{ EXPORT_STATES[tool.data?.state] || tool.data?.state || '状态待查询' }}<span v-if="tool.toolName === 'create_attendance_export'">；任务已创建，文件生成状态请到报表中心核对。</span></p>
                <el-button v-if="isExport(tool) && tool.data?.id" link type="primary" @click="emit('open-reports')">前往报表中心查看任务</el-button>
                <details class="assistant-receipt-detail"><summary>展开服务端回执</summary><pre>{{ receiptJson(tool.data) }}</pre></details>
              </template>
              <p v-if="!receiptPresentation(tool).restricted && tool.state !== 'SUCCEEDED'" role="status">该工具未成功执行<span v-if="tool.errorCode">（{{ tool.errorCode }}）</span>，请按本轮说明核对条件或权限。</p>
            </div>
            <div class="assistant-run-footer"><small>{{ localTimestamp(run.finishedAt || run.createdAt) }}</small><el-button v-if="run.state === 'RUNNING'" link type="primary" :loading="submission.phase === 'submitting' || detailLoading" @click="refreshRun(run)">刷新本轮状态</el-button></div>
          </div>
        </li></ol>
        <div v-if="submission.locked" class="assistant-recovery" aria-live="polite"><p v-if="submission.phase === 'submitting'" role="status">正在等待本轮结果，最长约 70 秒。请勿重复发送。</p><template v-else><p>{{ submission.phase === 'running' ? '本轮仍在处理，请刷新原请求确认结果。' : '本轮结果尚未确认，原问题与请求编号已保留。' }}</p><p class="attendance-note">恢复时先查找已保存的运行；尚未接收时才使用原编号继续，避免重复创建导出。</p><el-button type="primary" plain @click="recover">{{ submission.phase === 'running' ? '刷新原请求' : '恢复原请求' }}</el-button></template></div>
        <el-alert v-if="submission.error || sendError" :title="submission.error || sendError" :type="submission.phase === 'uncertain' ? 'warning' : 'error'" show-icon :closable="false" class="attendance-error" />
        <el-form class="assistant-composer" label-position="top" @submit.prevent="send"><el-form-item label="考勤问题" label-for="assistant-message"><el-input id="assistant-message" v-model="message" type="textarea" :rows="4" :maxlength="2000" show-word-limit resize="vertical" placeholder="例如：查询我本月的考勤汇总" :disabled="!selected || locked || !!latestRunning" /></el-form-item><div class="assistant-composer-actions"><p class="attendance-note">请勿输入密码、密钥或人脸信息。业务结论以服务端回执为准。</p><el-button type="primary" native-type="submit" :disabled="!canSend || !!latestRunning" :loading="submission.phase === 'submitting'">发送问题</el-button></div></el-form>
        <div v-if="selected && !locked && !latestRunning" class="assistant-examples" aria-label="示例问题"><el-button @click="useExample('查询我本月的考勤汇总')">本人本月汇总</el-button><el-button @click="useExample('解释我昨天的考勤')">解释昨天考勤</el-button><el-button v-if="userStore.getAuths.includes('records.export')" @click="useExample('导出我本月的考勤报表')">导出本人本月报表</el-button></div>
      </div>
    </div>
  </section>
</template>
