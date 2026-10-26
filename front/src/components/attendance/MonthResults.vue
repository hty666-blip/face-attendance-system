<script setup>
import {onMounted, ref} from 'vue'
import request from '@/utils/request'
import {dayLabel, eventLabel, requireData, shanghaiDate} from '@/utils/attendance.mjs'
const emit = defineEmits(['apply'])
const month = ref(shanghaiDate().slice(0, 7)), data = ref(null), loading = ref(false), error = ref('')
let generation = 0
const load = async () => {
  if (!/^\d{4}-\d{2}$/.test(month.value || '')) return
  const current = ++generation; loading.value = true; error.value = ''; data.value = null
  try {const result = requireData(await request.get('/attendance/month', {params: {month: month.value}})); if (current === generation) data.value = result}
  catch (reason) {if (current === generation) error.value = reason.message || '月度结果读取失败，请检查网络后重试。'}
  finally {if (current === generation) loading.value = false}
}
onMounted(load)
defineExpose({load})
</script>
<template>
  <section class="attendance-section" aria-label="我的月度考勤">
    <div class="attendance-toolbar">
      <label for="attendance-month">考勤月份</label>
      <el-date-picker id="attendance-month" v-model="month" type="month" value-format="YYYY-MM" format="YYYY年MM月" :clearable="false" @change="load" />
      <el-button :loading="loading" @click="load">刷新月度结果</el-button>
    </div>
    <p class="attendance-note">按北京时间与当日保存的规则计算。今天显示进行中；过去缺卡需核对，可申请补卡或请假。旧自由文本假期不折算为这里的请假事实。</p>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="attendance-error" />
    <p v-if="loading" role="status">正在读取 {{ month }} 的每日结果…</p>
    <p v-if="data?.statistics" class="attendance-note" role="status">本月预计工作日 {{ data.statistics.workingDays }} 天，已发生工作日 {{ data.statistics.elapsedWorkingDays }} 天；待核对缺卡 {{ data.statistics.missingDays }} 天，批准请假 {{ data.statistics.leaveDays }} 天，迟到 {{ data.statistics.lateMinutes }} 分钟，早退 {{ data.statistics.earlyMinutes }} 分钟。<span v-if="data.statistics.restrictedDays">另有 {{ data.statistics.restrictedDays }} 天历史不可见，未附事实。</span></p>
    <div v-if="data" class="attendance-table">
      <el-table :data="data.days || []" :max-height="600" stripe border empty-text="该月暂无可显示结果，请选择其他月份。">
        <el-table-column prop="businessDate" label="业务日期" width="116" />
        <el-table-column label="日结果" width="145"><template #default="{row}"><el-tag :type="row.missingIn || row.missingOut ? 'warning' : 'info'">{{ dayLabel(row) }}</el-tag></template></el-table-column>
        <el-table-column label="签到依据" min-width="210"><template #default="{row}"><span v-if="row.status === 'RESTRICTED'">历史范围不可见</span><span v-else>{{ eventLabel(row.checkIn) }}<strong v-if="row.missingIn"> · 缺签到</strong></span></template></el-table-column>
        <el-table-column label="签退依据" min-width="210"><template #default="{row}"><span v-if="row.status === 'RESTRICTED'">历史范围不可见</span><span v-else>{{ eventLabel(row.checkOut) }}<strong v-if="row.missingOut"> · 缺签退</strong></span></template></el-table-column>
        <el-table-column label="假期与异常" min-width="175"><template #default="{row}"><span v-if="row.status !== 'RESTRICTED'">批准请假 {{ row.leaveDays || 0 }} 天<br>迟到 {{ row.lateMinutes || 0 }} 分 / 早退 {{ row.earlyMinutes || 0 }} 分<span v-if="row.pendingCount"><br>另有 {{ row.pendingCount }} 项待审批</span></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="规则依据" min-width="180"><template #default="{row}"><span v-if="row.status === 'LEGACY'" class="attendance-basis">未适用新日规则</span><span v-else-if="row.status !== 'RESTRICTED'" class="attendance-basis">{{ row.workingDay ? '工作日' : '休息日' }}<template v-if="row.policyId"><br>规则 #{{ row.policyId }} · {{ row.startTime }}–{{ row.endTime }}</template></span><span v-else>—</span></template></el-table-column>
        <el-table-column label="处理" width="110"><template #default="{row}"><el-button v-if="row.missingIn || row.missingOut" link type="primary" @click="emit('apply', row)">申请补卡</el-button><span v-else>—</span></template></el-table-column>
      </el-table>
    </div>
  </section>
</template>
