<script setup>
import {computed, ref} from 'vue'
import {ElMessage} from 'element-plus'
import SafeHtml from '@/components/SafeHtml.vue'
import {downloadExport} from '@/utils/download'
import request from '@/utils/request'
import {useUserStore} from '@/stores/user'
import {localTimestamp, requireData} from '@/utils/attendance.mjs'
import '@/assets/css/attendance.css'
const userStore = useUserStore(), auths = userStore.getAuths
const rows = ref([]), users = ref([]), pageNum = ref(1), total = ref(0), loading = ref(false), error = ref('')
const selected = ref(null), opened = ref(false), decision = ref(''), remarks = ref(''), saving = ref(false), detailError = ref('')
const canReview = computed(() => selected.value?.stateRadio === '审核中' && ['ADMIN','manager'].includes(userStore.getUser.role) && auths.includes('leaveapply.edit') && Number(selected.value.userId) !== Number(userStore.getUserId))
const canCancel = computed(() => selected.value?.stateRadio === '审核中' && auths.includes('leaveapply.delete') && (userStore.getUser.role === 'ADMIN' || Number(selected.value.userId) === Number(userStore.getUserId)))
let generation = 0
const load = async () => {
  const current = ++generation; loading.value = true; error.value = ''; rows.value = []
  try {const data = requireData(await request.get('/leaveapply/page', {params: {pageNum: pageNum.value, pageSize: 10}})); if (current === generation) {rows.value = data.records || []; total.value = data.total || 0}}
  catch (reason) {if (current === generation) error.value = reason.message || '旧申请读取失败，请重试。'}
  finally {if (current === generation) loading.value = false}
}
request.get('/user').then(result => {if (result.code === '200') users.value = result.data || []}).catch(() => {})
const employeeName = id => users.value.find(item => Number(item.id) === Number(id))?.name || `员工 #${id}`
const show = row => {selected.value = row; decision.value = ''; remarks.value = ''; detailError.value = ''; opened.value = true}
const process = async cancel => {
  if (saving.value || !selected.value) return
  if (!cancel && !decision.value) {ElMessage.warning('请选择通过或不通过。'); return}
  saving.value = true; detailError.value = ''
  try {
    requireData(cancel ? await request.delete(`/leaveapply/${selected.value.id}`) : await request.put('/leaveapply', {id: selected.value.id, stateRadio: decision.value, remarks: remarks.value.trim()}))
    ElMessage.success(cancel ? '旧申请已撤回。' : '旧申请审批已保存。'); opened.value = false; load()
  } catch (reason) {detailError.value = reason.businessError ? `${reason.message} 请关闭后刷新列表核对最新状态。` : '操作结果未确认，请先关闭并刷新列表核对。旧申请不计入新日结果。'}
  finally {saving.value = false}
}
load()
</script>
<template>
  <main class="attendance-workspace">
    <div class="attendance-heading"><div><h1>旧请假历史</h1><p>旧记录缺少明确起止日期，保留读取与原审批，不折算新月度考勤。</p></div><el-button type="primary" @click="$router.push('/attendance')">前往新请假与补卡</el-button></div>
    <div class="attendance-toolbar"><el-button :loading="loading" @click="load">刷新旧申请</el-button><el-button v-if="auths.includes('leaveapply.export')" @click="downloadExport('/leaveapply/export')">导出旧申请</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div class="attendance-table"><el-table :data="rows" stripe border empty-text="暂无可见的旧请假申请。新的日 / 半日请假请在工作台提交。"><el-table-column label="员工" min-width="130"><template #default="{row}">{{ employeeName(row.userId) }}</template></el-table-column><el-table-column prop="periods" label="原时长文本" min-width="130" /><el-table-column prop="typeRadio" label="类型" width="85" /><el-table-column prop="stateRadio" label="状态" width="100" /><el-table-column prop="remarks" label="原审核说明" min-width="180" /><el-table-column label="申请时间" min-width="175"><template #default="{row}">{{ localTimestamp(row.createTime) }}</template></el-table-column><el-table-column label="操作" width="130"><template #default="{row}"><el-button link type="primary" @click="show(row)">查看 / 原审批</el-button></template></el-table-column></el-table></div>
    <el-pagination v-model:current-page="pageNum" :total="total" :page-size="10" layout="prev,pager,next,total" @current-change="load" />
    <el-dialog v-model="opened" title="旧请假申请" width="min(720px,94vw)" :show-close="!saving" :close-on-press-escape="!saving" :close-on-click-modal="false">
      <div v-if="selected" class="attendance-detail"><p>申请人：{{ employeeName(selected.userId) }} · {{ selected.typeRadio }} · 原时长：{{ selected.periods }} · {{ selected.stateRadio }}</p><SafeHtml :html="selected.content || ''" /><el-alert title="这条旧申请没有可靠业务日期，不用于推算新日考勤结论。" type="info" :closable="false" show-icon />
        <el-form v-if="canReview" label-position="top" :disabled="saving"><el-form-item label="原申请审批结果"><el-radio-group v-model="decision"><el-radio label="通过">通过</el-radio><el-radio label="不通过">不通过</el-radio></el-radio-group></el-form-item><el-form-item label="审核说明"><el-input v-model="remarks" maxlength="500" type="textarea" :rows="3" /></el-form-item></el-form>
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <div class="attendance-actions"><el-popconfirm v-if="canReview" title="确认保存此旧申请审批结果？" @confirm="process(false)"><template #reference><el-button type="primary" :loading="saving">保存旧审批</el-button></template></el-popconfirm><el-popconfirm v-if="canCancel" title="确认撤回这条尚未审批的旧申请？" @confirm="process(true)"><template #reference><el-button :disabled="saving">撤回旧申请</el-button></template></el-popconfirm></div>
      </div><template #footer><el-button :disabled="saving" @click="opened = false; load()">关闭并刷新</el-button></template>
    </el-dialog>
  </main>
</template>
