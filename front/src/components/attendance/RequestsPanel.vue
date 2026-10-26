<script setup>
import {computed, onMounted, ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {useUserStore} from '@/stores/user'
import request from '@/utils/request'
import {localTimestamp, requestRange, REQUEST_STATUS, requireData} from '@/utils/attendance.mjs'
const props = defineProps({reviewer: Boolean})
const emit = defineEmits(['changed'])
const userStore = useUserStore()
const scope = ref('mine'), pageNum = ref(1), pageSize = 10, rows = ref([]), total = ref(0), loading = ref(false), error = ref('')
const opened = ref(false), detail = ref(null), detailId = ref(null), detailLoading = ref(false), detailError = ref(''), comment = ref(''), deciding = ref(false), pendingOperation = ref(null)
let generation = 0, detailGeneration = 0
const current = computed(() => detail.value?.request)
const own = computed(() => Number(current.value?.userId) === Number(userStore.getUserId))
const pending = computed(() => current.value?.state === 'PENDING')
const canReview = computed(() => props.reviewer && !own.value && (current.value?.deptId !== 0 || userStore.getUser.role === 'ADMIN') && userStore.getAuths.includes(current.value?.kind === 'LEAVE' ? 'leaveapply.edit' : 'records.edit'))
const load = async () => {
  const active = ++generation; loading.value = true; error.value = ''; rows.value = []
  try {const data = requireData(await request.get('/attendance/requests', {params: {scope: scope.value, pageNum: pageNum.value, pageSize}})); if (active === generation) {rows.value = data.records || []; total.value = data.total || 0}}
  catch (reason) {if (active === generation) error.value = reason.message || '申请列表读取失败，请检查网络后重试。'}
  finally {if (active === generation) loading.value = false}
}
watch(scope, () => {pageNum.value = 1; load()})
const loadDetail = async id => {
  const active = ++detailGeneration; detailLoading.value = true; detailId.value = id
  try {const data = requireData(await request.get(`/attendance/requests/${id}`)); if (active === detailGeneration) detail.value = data}
  catch (reason) {if (active === detailGeneration) detailError.value = reason.message || '详情读取失败，请重试。'}
  finally {if (active === detailGeneration) detailLoading.value = false}
}
const show = row => {detail.value = null; detailError.value = ''; comment.value = ''; pendingOperation.value = null; opened.value = true; loadDetail(row.id)}
const decide = async decision => {
  if (deciding.value || !current.value) return
  if (!pendingOperation.value) {
    if (!pending.value) return
    if (decision !== 'CANCELLED' && !canReview.value) {ElMessage.warning('没有该类申请的审批权限，或申请为本人提交。'); return}
    pendingOperation.value = Object.freeze({url: `/attendance/requests/${current.value.id}/${decision === 'CANCELLED' ? 'cancel' : 'decision'}`, data: decision === 'CANCELLED' ? {version: current.value.version} : {version: current.value.version, decision, comment: comment.value.trim()}})
  }
  const operation = pendingOperation.value
  deciding.value = true; detailError.value = ''
  try {
    requireData(await request.post(operation.url, operation.data)); pendingOperation.value = null
    ElMessage.success('申请状态已更新。'); await loadDetail(current.value.id); load(); emit('changed')
  } catch (reason) {
    if (reason.businessError) {
      pendingOperation.value = null
      detailError.value = reason.conflict ? `${reason.message} 页面将读取最新状态，请核对后再操作。` : reason.message
      if (reason.conflict) {await loadDetail(current.value.id); load()}
    } else detailError.value = '尚未收到操作结果，状态可能已经更新。请重试本次操作，或关闭后核对申请列表。'
  } finally {deciding.value = false}
}
onMounted(load)
defineExpose({load})
</script>
<template>
  <section class="attendance-section" aria-label="考勤申请列表">
    <div class="attendance-toolbar">
      <el-radio-group v-model="scope" aria-label="申请范围"><el-radio-button label="mine">我的申请</el-radio-button><el-radio-button v-if="reviewer" label="pending">待我审批</el-radio-button><el-radio-button v-if="reviewer" label="all">可见范围全部</el-radio-button></el-radio-group>
      <el-button :loading="loading" @click="load">刷新申请列表</el-button>
    </div>
    <p class="attendance-note">新申请按明确日期与半日审批。旧自由文本假期保留在原请假页面，不混入本列表。主管只能审批所管范围内他人的申请。</p>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="attendance-error" />
    <p v-if="loading" role="status">正在读取申请…</p>
    <div class="attendance-table"><el-table :data="rows" stripe border empty-text="当前范围没有申请。新申请可在“提交申请”页签填写。">
      <el-table-column label="申请人" width="135"><template #default="{row}">{{ row.applicantName || `员工 #${row.userId}` }}</template></el-table-column>
      <el-table-column label="类型" width="130"><template #default="{row}">{{ row.kind === 'CORRECTION' ? '补卡' : `${row.leaveType || ''}请假` }}<span v-if="row.kind === 'LEAVE' && row.leaveDays != null"><br>{{ row.leaveDays }} 个工作日</span></template></el-table-column>
      <el-table-column label="申请日期与动作" min-width="270"><template #default="{row}">{{ requestRange(row) }}</template></el-table-column>
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.state === 'PENDING' ? 'warning' : row.state === 'APPROVED' ? 'success' : 'info'">{{ REQUEST_STATUS[row.state] || row.state }}</el-tag></template></el-table-column>
      <el-table-column label="提交时间" min-width="170"><template #default="{row}">{{ localTimestamp(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="115"><template #default="{row}"><el-button link type="primary" @click="show(row)">查看与处理</el-button></template></el-table-column>
    </el-table></div>
    <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev,pager,next,total" @current-change="load" />
    <el-dialog v-model="opened" title="申请详情与处理历史" width="min(760px, 94vw)" top="5vh" class="attendance-dialog" append-to-body :close-on-click-modal="false" :show-close="!deciding" :close-on-press-escape="!deciding" @closed="load">
      <p v-if="detailLoading" role="status">正在读取申请详情…</p>
      <el-alert v-if="detailError" :title="detailError" type="error" show-icon :closable="false" class="attendance-error" />
      <el-button v-if="!current && detailId && !detailLoading" @click="detailError = ''; loadDetail(detailId)">重试读取详情</el-button>
      <div v-if="current" class="attendance-detail">
        <dl class="attendance-facts"><dt>申请编号</dt><dd>#{{ current.id }} · 版本 {{ current.version }}</dd><dt>申请人 / 当时部门</dt><dd>{{ current.applicantName || `员工 #${current.userId}` }} / 部门 #{{ current.deptId ?? '未记录' }}</dd><dt>申请范围</dt><dd>{{ requestRange(current) }}</dd><dt>状态</dt><dd>{{ REQUEST_STATUS[current.state] || current.state }}</dd><dt>申请原因</dt><dd>{{ current.reason }}</dd><dt v-if="current.decisionAt">最终处理</dt><dd v-if="current.decisionAt">{{ localTimestamp(current.decisionAt) }} · {{ current.decisionComment || '未填写说明' }}</dd></dl>
        <p v-if="current.kind === 'LEAVE' && current.leaveDays != null">服务端核定请假时长：{{ current.leaveDays }} 个工作日（以适用工作日安排为依据）。</p>
        <p v-if="current.deptId === 0" class="attendance-note">申请日期缺少可靠的历史部门归属，需要管理员复核。</p>
        <h3>状态历史</h3>
        <ol v-if="detail.history?.length" class="attendance-history"><li v-for="(item,index) in detail.history" :key="item.id || index">{{ localTimestamp(item.createdAt) }} · {{ item.fromState ? `${REQUEST_STATUS[item.fromState] || item.fromState} → ` : '' }}{{ REQUEST_STATUS[item.toState] || item.toState }} · 操作人 #{{ item.actorId }}<div v-if="item.comment" class="attendance-basis">{{ item.comment }}</div></li></ol>
        <p v-else class="attendance-note">暂无状态变更记录。</p>
        <p v-if="pending && own && reviewer" class="attendance-note">本人申请须由其他有权限的人审批。</p>
        <template v-if="pending && canReview && !pendingOperation"><el-form label-position="top"><el-form-item label="审批说明"><el-input v-model="comment" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="deciding" placeholder="填写审批依据或驳回原因。" /></el-form-item></el-form></template>
        <div v-if="pending && !pendingOperation" class="attendance-actions"><el-popconfirm v-if="own" title="确认撤回此申请？撤回后不能继续审批。" @confirm="decide('CANCELLED')"><template #reference><el-button :disabled="deciding" :loading="deciding">撤回申请</el-button></template></el-popconfirm><template v-if="canReview"><el-popconfirm title="批准后会更新考勤事实，确认批准？" @confirm="decide('APPROVED')"><template #reference><el-button type="primary" :disabled="deciding" :loading="deciding">批准申请</el-button></template></el-popconfirm><el-popconfirm title="确认驳回此申请？" @confirm="decide('REJECTED')"><template #reference><el-button type="danger" plain :disabled="deciding">驳回申请</el-button></template></el-popconfirm></template></div>
        <el-button v-if="pendingOperation" type="primary" :loading="deciding" @click="decide()">重试本次操作</el-button>
      </div>
      <template #footer><el-button :disabled="deciding" @click="opened = false">关闭并核对列表</el-button></template>
    </el-dialog>
  </section>
</template>
