<script setup>
import {computed} from 'vue'
import {useUserStore} from '@/stores/user'
import PolicyCalendar from '@/components/attendance/PolicyCalendar.vue'
import '@/assets/css/attendance.css'
const userStore = useUserStore()
const editable = computed(() => userStore.getUser.role === 'ADMIN' && userStore.getAuths.includes('rules.edit'))
</script>
<template>
  <main class="attendance-workspace">
    <div class="attendance-heading"><div><h1>考勤规则与工作日</h1><p>使用统一的规则版本与日历。旧规则编辑入口已关闭，过去日结果保持原有依据。</p></div><el-button @click="$router.push('/attendance')">返回考勤工作台</el-button></div>
    <el-alert v-if="!editable" title="当前为只读模式，只有具有规则编辑权限的管理员可以配置未来规则与工作日。" type="info" :closable="false" show-icon />
    <PolicyCalendar :editable="editable" />
  </main>
</template>
