<script setup>
import {onMounted, reactive, ref} from 'vue'
import {ElMessage} from 'element-plus'
import request from '@/utils/request'
import {nextDate, pickerDate, requireData, shanghaiDate} from '@/utils/attendance.mjs'
const props = defineProps({editable: Boolean})
const today = shanghaiDate(), tomorrow = nextDate(today)
const month = ref(today.slice(0,7)), policies = ref([]), calendar = ref([])
const policyError = ref(''), calendarError = ref(''), policyLoading = ref(false), calendarLoading = ref(false), policySaving = ref(false), calendarSaving = ref(false)
const policy = reactive({effectiveFrom: tomorrow, startTime: '09:00:00', endTime: '18:00:00', graceMinutes: 0})
const override = reactive({date: tomorrow, workingDay: true, note: ''})
const futureOnly = date => pickerDate(date) <= today
const arrayData = data => Array.isArray(data) ? data : data?.records || data?.days || []
const loadPolicies = async () => {
  policyLoading.value = true; policyError.value = ''
  try {policies.value = arrayData(requireData(await request.get('/attendance/policies')))}
  catch (reason) {policyError.value = reason.message || '规则版本读取失败，请重试。'}
  finally {policyLoading.value = false}
}
let generation = 0
const loadCalendar = async () => {
  if (!month.value) return
  const current = ++generation; calendarLoading.value = true; calendarError.value = ''
  try {const data = requireData(await request.get('/attendance/calendar', {params: {month: month.value}})); if (current === generation) calendar.value = arrayData(data)}
  catch (reason) {if (current === generation) calendarError.value = reason.message || '工作日调整读取失败，请重试。'}
  finally {if (current === generation) calendarLoading.value = false}
}
const savePolicy = async () => {
  if (!props.editable || policySaving.value) return
  if (!policy.effectiveFrom || policy.effectiveFrom <= today || !policy.startTime || !policy.endTime || policy.startTime >= '12:00:00' || policy.endTime <= '12:00:00') {policyError.value = '请设置明日或更晚生效的规则，开始时间须早于12:00，结束须晚于12:00。'; return}
  policySaving.value = true; policyError.value = ''
  try {requireData(await request.post('/attendance/policies', {...policy})); ElMessage.success('已追加未来生效规则。'); await loadPolicies()}
  catch (reason) {policyError.value = reason.businessError ? `${reason.message}${reason.conflict ? ' 请刷新版本列表后核对。' : ''}` : '提交结果未确认，请先刷新规则版本列表核对，避免重复提交。'}
  finally {policySaving.value = false}
}
const saveCalendar = async () => {
  if (!props.editable || calendarSaving.value) return
  if (!override.date || override.date <= today) {calendarError.value = '只能调整明日或更晚的工作日安排。'; return}
  if (!override.note.trim()) {calendarError.value = '请填写工作日调整说明，便于后续核对。'; return}
  calendarSaving.value = true; calendarError.value = ''
  try {requireData(await request.post('/attendance/calendar', {...override})); ElMessage.success('未来日期安排已保存。'); month.value = override.date.slice(0,7); await loadCalendar()}
  catch (reason) {calendarError.value = reason.businessError ? reason.message : '提交结果未确认，请刷新该月安排核对后再操作。'}
  finally {calendarSaving.value = false}
}
onMounted(() => {loadPolicies(); loadCalendar()})
</script>
<template>
  <section class="attendance-section" aria-label="全局规则与工作日调整">
    <p class="attendance-note">全组织固定日班，默认周一至周五出勤。已发生日期的规则与日历保留；只能追加未来生效规则、调整未来日期。半日分界为北京时间12:00。</p>
    <div class="attendance-policy-grid">
      <section><h2>日班规则版本</h2><el-button :loading="policyLoading" @click="loadPolicies">刷新规则版本</el-button>
        <el-alert v-if="policyError" :title="policyError" type="error" :closable="false" show-icon class="attendance-error" />
        <div class="attendance-table"><el-table :data="policies" :max-height="320" border empty-text="暂无可读取的规则版本。"><el-table-column prop="effectiveFrom" label="生效日期" min-width="115" /><el-table-column prop="startTime" label="上班" min-width="95" /><el-table-column prop="endTime" label="下班" min-width="95" /><el-table-column prop="graceMinutes" label="宽限分钟" width="100" /></el-table></div>
        <el-form v-if="editable" label-position="top" class="attendance-form" :disabled="policySaving" @submit.prevent="savePolicy"><el-form-item label="未来生效日期" required><el-date-picker v-model="policy.effectiveFrom" value-format="YYYY-MM-DD" :disabled-date="futureOnly" :clearable="false" /></el-form-item><div class="attendance-field-pair"><el-form-item label="上班时间（早于12:00）" required><el-time-picker v-model="policy.startTime" value-format="HH:mm:ss" format="HH:mm" :clearable="false" /></el-form-item><el-form-item label="下班时间（晚于12:00）" required><el-time-picker v-model="policy.endTime" value-format="HH:mm:ss" format="HH:mm" :clearable="false" /></el-form-item></div><el-form-item label="迟到 / 早退宽限分钟"><el-input-number v-model="policy.graceMinutes" :min="0" :max="60" :precision="0" /></el-form-item><el-button type="primary" :loading="policySaving" @click="savePolicy">追加未来规则</el-button></el-form>
      </section>
      <section><h2>工作日调整</h2><div class="attendance-toolbar"><el-date-picker v-model="month" type="month" value-format="YYYY-MM" :clearable="false" aria-label="查看工作日调整月份" @change="loadCalendar" /><el-button :loading="calendarLoading" @click="loadCalendar">刷新安排</el-button></div>
        <el-alert v-if="calendarError" :title="calendarError" type="error" :closable="false" show-icon class="attendance-error" />
        <div class="attendance-table"><el-table :data="calendar" :max-height="320" border empty-text="暂无该月日历，请刷新重试。"><el-table-column prop="date" label="日期" min-width="115" /><el-table-column label="安排" width="90"><template #default="{row}">{{ row.workingDay ? '工作日' : '休息日' }}</template></el-table-column><el-table-column label="依据" width="95"><template #default="{row}">{{ row.overridden ? '人工调整' : '默认周历' }}</template></el-table-column><el-table-column prop="note" label="说明" min-width="150" /></el-table></div>
        <el-form v-if="editable" label-position="top" class="attendance-form" :disabled="calendarSaving" @submit.prevent="saveCalendar"><el-form-item label="未来调整日期" required><el-date-picker v-model="override.date" value-format="YYYY-MM-DD" :disabled-date="futureOnly" :clearable="false" /></el-form-item><el-form-item label="当天安排"><el-radio-group v-model="override.workingDay"><el-radio :label="true">工作日</el-radio><el-radio :label="false">休息日</el-radio></el-radio-group></el-form-item><el-form-item label="调整说明" required><el-input v-model="override.note" maxlength="200" show-word-limit placeholder="例如：调休补班" /></el-form-item><el-button type="primary" :loading="calendarSaving" @click="saveCalendar">保存未来安排</el-button></el-form>
      </section>
    </div>
  </section>
</template>
