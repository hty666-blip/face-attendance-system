<script setup>
import {computed, ref} from 'vue'
import {ElMessage} from 'element-plus'
import {useUserStore} from '@/stores/user'
import MonthResults from '@/components/attendance/MonthResults.vue'
import RequestForm from '@/components/attendance/RequestForm.vue'
import RequestsPanel from '@/components/attendance/RequestsPanel.vue'
import PolicyCalendar from '@/components/attendance/PolicyCalendar.vue'
import '@/assets/css/attendance.css'
const userStore = useUserStore(), auths = computed(() => userStore.getAuths)
const canRead = computed(() => auths.value.includes('records.list') || auths.value.includes('leaveapply.list'))
const canCorrection = computed(() => auths.value.includes('records.add')), canLeave = computed(() => auths.value.includes('leaveapply.add'))
const reviewer = computed(() => ['ADMIN','manager'].includes(userStore.getUser.role) && (auths.value.includes('leaveapply.edit') || auths.value.includes('records.edit')))
const administrator = computed(() => userStore.getUser.role === 'ADMIN' && auths.value.includes('rules.edit'))
const active = ref(auths.value.includes('records.list') ? 'month' : 'requests'), seed = ref(null), monthPanel = ref(), requestsPanel = ref()
const apply = day => {
  if (!canCorrection.value) {ElMessage.warning('当前账号没有补卡申请权限，请联系管理员。'); return}
  seed.value = {...day}; active.value = 'apply'
}
const changed = () => {monthPanel.value?.load(); requestsPanel.value?.load()}
const decided = () => {monthPanel.value?.load()}
const created = () => {changed(); active.value = 'requests'}
</script>
<template>
  <main class="attendance-workspace">
    <div class="attendance-heading"><div><h1>考勤工作台</h1><p>核对每日结果、处理缺卡与请假，查看每一次申请的审批依据。</p></div><el-button v-if="auths.includes('records.list')" @click="$router.push('/records')">原始事件与扫脸打卡</el-button></div>
    <el-alert v-if="!canRead" title="当前账号没有考勤或请假查询权限，请联系管理员。" type="warning" :closable="false" show-icon />
    <el-tabs v-else v-model="active" @tab-change="name => {if (name === 'month') monthPanel?.load(); if (name === 'requests') requestsPanel?.load()}">
      <el-tab-pane v-if="auths.includes('records.list')" label="我的月度考勤" name="month"><MonthResults ref="monthPanel" @apply="apply" /></el-tab-pane>
      <el-tab-pane label="申请与审批" name="requests"><RequestsPanel ref="requestsPanel" :reviewer="reviewer" @changed="decided" /></el-tab-pane>
      <el-tab-pane v-if="canCorrection || canLeave" label="提交申请" name="apply"><RequestForm :seed="seed" :can-correction="canCorrection" :can-leave="canLeave" @created="created" /></el-tab-pane>
      <el-tab-pane v-if="administrator" label="规则与工作日" name="configuration"><PolicyCalendar :editable="administrator" /></el-tab-pane>
    </el-tabs>
  </main>
</template>
