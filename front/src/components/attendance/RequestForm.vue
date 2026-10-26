<script setup>
import {computed, reactive, watch} from 'vue'
import {ElMessage} from 'element-plus'
import request from '@/utils/request'
import {RequestSubmission, shanghaiDate} from '@/utils/attendance.mjs'
const props = defineProps({seed: Object, canCorrection: Boolean, canLeave: Boolean})
const emit = defineEmits(['created'])
const initial = () => ({kind: props.canCorrection ? 'CORRECTION' : 'LEAVE', reason: '', businessDate: '', action: 'IN', correctionTime: '', startDate: shanghaiDate(), startHalf: 'AM', endDate: shanghaiDate(), endHalf: 'PM', leaveType: '事假'})
const form = reactive(initial()), submission = reactive(new RequestSubmission(request))
const locked = computed(() => ['submitting', 'uncertain'].includes(submission.phase))
const error = computed(() => submission.error)
watch(() => props.seed, seed => {
  if (!seed || locked.value) return
  submission.reset(); Object.assign(form, initial(), {businessDate: seed.businessDate, action: seed.missingIn ? 'IN' : 'OUT'})
}, {immediate: true})
const send = async () => {
  if (submission.phase === 'submitting') return
  let draft
  if (submission.phase !== 'uncertain') {
    const common = {kind: form.kind, reason: form.reason.trim()}
    if (!common.reason) {ElMessage.warning('请填写申请原因。'); return}
    if (form.kind === 'CORRECTION') {
      if (!props.canCorrection) {ElMessage.warning('没有补卡申请权限。'); return}
      if (!form.businessDate || !form.correctionTime) {ElMessage.warning('请选择补卡日期与实际发生时间。'); return}
      if (!form.correctionTime.startsWith(form.businessDate)) {ElMessage.warning('实际发生时间必须位于所选业务日期。'); return}
      draft = {...common, businessDate: form.businessDate, action: form.action, correctionTime: form.correctionTime}
    } else {
      if (!props.canLeave) {ElMessage.warning('没有请假申请权限。'); return}
      if (!form.startDate || !form.endDate) {ElMessage.warning('请选择请假起止日期。'); return}
      if (`${form.startDate}${form.startHalf === 'AM' ? 0 : 1}` > `${form.endDate}${form.endHalf === 'AM' ? 0 : 1}`) {ElMessage.warning('请假结束半日不能早于开始半日。'); return}
      draft = {...common, startDate: form.startDate, startHalf: form.startHalf, endDate: form.endDate, endHalf: form.endHalf, leaveType: form.leaveType}
    }
  }
  try {
    if (await submission.submit(draft)) {ElMessage.success('申请已提交，等待审批。'); Object.assign(form, initial()); emit('created'); submission.reset()}
  } catch (reason) {ElMessage.error(reason.message || '浏览器无法准备本次申请。')}
}
</script>
<template>
  <section class="attendance-section" aria-label="新建考勤申请">
    <p class="attendance-note">补卡限已启用新口径后、过去 30 天的工作日缺失动作；请填写实际发生时间。请假按上午 / 下午计算，开始不早于过去30天、结束不晚于未来366天，单次最多31个自然日。审批通过后更新日结果。</p>
    <el-form :model="form" label-position="top" class="attendance-form" :disabled="locked" @submit.prevent="send">
      <el-form-item label="申请类型"><el-radio-group v-model="form.kind"><el-radio-button v-if="canCorrection" label="CORRECTION">补卡申请</el-radio-button><el-radio-button v-if="canLeave" label="LEAVE">请假申请</el-radio-button></el-radio-group></el-form-item>
      <template v-if="form.kind === 'CORRECTION'">
        <div class="attendance-field-pair"><el-form-item label="业务日期（北京时间）" required><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" placeholder="选择过去的缺卡日期" /></el-form-item><el-form-item label="缺失动作" required><el-select v-model="form.action"><el-option label="签到" value="IN" /><el-option label="签退" value="OUT" /></el-select></el-form-item></div>
        <el-form-item label="实际发生时间（北京时间）" required><el-date-picker v-model="form.correctionTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择实际签到或签退时间" /></el-form-item>
      </template>
      <template v-else>
        <el-form-item label="请假类型"><el-select v-model="form.leaveType"><el-option v-for="type in ['事假','病假','婚假','丧假']" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <div class="attendance-field-pair"><el-form-item label="开始日期" required><el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" type="date" /></el-form-item><el-form-item label="开始半日"><el-select v-model="form.startHalf"><el-option label="上午" value="AM" /><el-option label="下午" value="PM" /></el-select></el-form-item></div>
        <div class="attendance-field-pair"><el-form-item label="结束日期" required><el-date-picker v-model="form.endDate" value-format="YYYY-MM-DD" type="date" /></el-form-item><el-form-item label="结束半日"><el-select v-model="form.endHalf"><el-option label="上午" value="AM" /><el-option label="下午" value="PM" /></el-select></el-form-item></div>
      </template>
      <el-form-item label="申请原因" required><el-input v-model="form.reason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="说明缺卡或请假的实际情况；本入口不收证明附件。" /></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="attendance-error" />
    <div class="attendance-actions"><el-button type="primary" :loading="submission.phase === 'submitting'" @click="send">{{ submission.phase === 'uncertain' ? '重试本次提交' : '提交申请' }}</el-button><span v-if="submission.phase === 'uncertain'" role="status">重试会沿用原申请内容与编号。</span></div>
  </section>
</template>
