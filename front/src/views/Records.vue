<script setup>
import {nextTick, reactive, ref, shallowRef, onBeforeUnmount, watch} from 'vue'
import {ElMessage} from 'element-plus'
import {downloadExport} from '@/utils/download'
import CameraCapture from '@/components/CameraCapture.vue'
import {CheckInAttempt} from '@/utils/face-checkin.mjs'
import {ACTION_LABELS, SOURCE_LABELS, localTimestamp, requireData} from '@/utils/attendance.mjs'
import '@/assets/css/face-dialog.css'
import '@/assets/css/attendance.css'
import request from '@/utils/request'
import config from '../../config'
import {useUserStore} from '@/stores/user'
import AMapLoader from '@amap/amap-jsapi-loader'
const userStore = useUserStore(), auths = userStore.getAuths
const rows = ref([]), pageNum = ref(1), pageSize = ref(10), total = ref(0), loading = ref(false), listError = ref('')
const userOptions = ref([])
let listGeneration = 0
const load = async () => {
  const generation = ++listGeneration; loading.value = true; listError.value = ''
  try {const data = requireData(await request.get('/records/page', {params: {pageNum: pageNum.value, pageSize: pageSize.value}})); if (generation === listGeneration) {rows.value = data.records || []; total.value = data.total || 0}}
  catch (reason) {if (generation === listGeneration) {rows.value = []; listError.value = reason.message || '原始事件读取失败，请检查网络后重试。'}}
  finally {if (generation === listGeneration) loading.value = false}
}
request.get('/user').then(response => {if (response.code === '200') userOptions.value = response.data || []}).catch(() => {})
load()
const employeeName = id => userOptions.value.find(item => Number(item.id) === Number(id))?.name || `员工 #${id}`
const exportData = () => downloadExport('/records/export')
const dialogFaceFormVisible = ref(false), today = ref(null), todayLoading = ref(false), todayError = ref(''), action = ref('')
const checkIn = reactive(new CheckInAttempt(request)), camera = ref()
const locationError = ref(''), locating = ref(false), address = ref('')
const map = shallowRef(null)
let locationGeneration = 0
const stopLocation = () => {
  locationGeneration++; locating.value = false
  map.value?.destroy(); map.value = null
}
const locate = async () => {
  stopLocation(); address.value = ''; locationError.value = ''; locating.value = true
  const generation = locationGeneration
  const current = () => generation === locationGeneration && dialogFaceFormVisible.value
  if (!config.key || !config.securityJsCode) {
    locationError.value = '地图尚未配置，请联系管理员填写地图配置后重试。'; locating.value = false; return
  }
  try {
    window._AMapSecurityConfig = {securityJsCode: config.securityJsCode}
    const AMap = await AMapLoader.load({key: config.key, version: '2.0', plugins: ['AMap.Geolocation', 'AMap.Geocoder']})
    if (!current()) return
    const geolocation = new AMap.Geolocation({enableHighAccuracy: true, timeout: 10000, maximumAge: 0})
    geolocation.getCurrentPosition((status, result) => {
      if (!current()) return
      if (status !== 'complete' || !result.position) {
        locationError.value = '定位失败，请允许浏览器访问位置后重新定位。'; locating.value = false; return
      }
      const {lng, lat} = result.position
      const geocoder = new AMap.Geocoder({radius: 1000})
      geocoder.getAddress([lng, lat], (geoStatus, response) => {
        if (!current()) return
        locating.value = false
        if (geoStatus !== 'complete' || !response.regeocode?.formattedAddress) {
          locationError.value = '暂时无法获取当前位置的地址，请重新定位。'; return
        }
        address.value = `${response.regeocode.formattedAddress}(经度${lng},纬度${lat})`
        map.value = new AMap.Map('checkin-map', {zoom: 15, center: [lng, lat]})
        new AMap.Marker({position: [lng, lat], map: map.value})
      })
    })
  } catch {
    if (current()) {locationError.value = '地图服务加载失败，请检查网络和地图配置后重试。'; locating.value = false}
  }
}

let todayGeneration = 0
const loadToday = async () => {
  checkIn.clear(); camera.value?.reset(); action.value = ''; today.value = null; todayError.value = ''; todayLoading.value = true
  const generation = ++todayGeneration
  try {const result = requireData(await request.get('/attendance/today')); if (generation === todayGeneration && dialogFaceFormVisible.value) today.value = result}
  catch (reason) {if (generation === todayGeneration) todayError.value = reason.message || '无法读取今日可用动作，请检查网络后重试。'}
  finally {if (generation === todayGeneration) todayLoading.value = false}
}
const faceRecord = () => {dialogFaceFormVisible.value = true}
const prepareCheckIn = async () => {
  if (checkIn.attempt || !today.value?.actions?.some(item => item.action === action.value && item.allowed)) return
  camera.value?.reset(); await checkIn.prepare(action.value)
}
const completeCheckIn = () => {ElMessage.success(`${ACTION_LABELS[checkIn.action] || '打卡'}成功`); dialogFaceFormVisible.value = false; load()}
const verifyCapture = async images => {if (await checkIn.capture(images[0], address.value)) completeCheckIn()}
const retryAttempt = async () => {if (await checkIn.submit()) completeCheckIn()}
watch(dialogFaceFormVisible, async open => {
  if (open) {loadToday(); await nextTick(); if (dialogFaceFormVisible.value) locate()}
  else {todayGeneration++; stopLocation(); checkIn.clear(); address.value = ''; today.value = null; load()}
})
onBeforeUnmount(() => {todayGeneration++; listGeneration++; stopLocation(); checkIn.clear()})
</script>
<template>
  <main class="attendance-workspace">
    <div class="attendance-heading"><div><h1>原始考勤事件</h1><p>人脸打卡与批准补卡形成的事实保留原始来源。日结果、补卡与请假在考勤工作台处理。</p></div><div class="attendance-actions"><el-button @click="$router.push('/attendance')">考勤工作台</el-button><el-button v-if="auths.includes('records.add')" type="primary" @click="faceRecord">签到 / 签退扫脸</el-button><el-button v-if="auths.includes('records.export')" @click="exportData">导出原始事件</el-button></div></div>
    <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon class="attendance-error" />
    <el-button :loading="loading" @click="load">刷新事件</el-button>
    <div class="attendance-table"><el-table :data="rows" stripe border empty-text="暂无可见原始事件。可前往工作台核对每日结果。">
      <el-table-column label="员工" min-width="130"><template #default="{row}">{{ employeeName(row.userId) }}</template></el-table-column>
      <el-table-column prop="businessDate" label="业务日期" min-width="115"><template #default="{row}">{{ row.businessDate || '旧记录未归日' }}</template></el-table-column>
      <el-table-column label="动作" width="90"><template #default="{row}">{{ ACTION_LABELS[row.action] || '旧记录' }}</template></el-table-column>
      <el-table-column label="来源" width="110"><template #default="{row}">{{ SOURCE_LABELS[row.source] || row.source || '旧记录' }}</template></el-table-column>
      <el-table-column label="实际发生时间（北京）" min-width="185"><template #default="{row}">{{ localTimestamp(row.occurredAt || row.createTime) }}</template></el-table-column>
      <el-table-column label="写入时间（北京）" min-width="180"><template #default="{row}">{{ localTimestamp(row.createTime) }}</template></el-table-column>
      <el-table-column prop="address" label="打卡地点 / 说明" min-width="220" />
    </el-table></div>
    <el-pagination v-model:current-page="pageNum" :page-size="pageSize" :total="total" layout="prev,pager,next,total" @current-change="load" />
    <el-dialog v-model="dialogFaceFormVisible" width="min(720px, 94vw)" title="人脸签到 / 签退" top="5vh" class="face-dialog" append-to-body destroy-on-close :close-on-click-modal="false" :close-on-press-escape="checkIn.phase !== 'verifying'" :show-close="checkIn.phase !== 'verifying'">
      <div class="checkin-content">
        <p v-if="todayLoading" role="status">正在核对今日业务日、工作安排与可用动作…</p>
        <el-alert v-if="todayError" :title="todayError" type="error" :closable="false" show-icon />
        <el-button v-if="todayError" @click="loadToday">重新读取今日安排</el-button>
        <template v-if="today"><p>业务日期：{{ today.businessDate }} · 北京时间。请选择本次动作：</p>
          <el-radio-group v-model="action" :disabled="!!checkIn.attempt || checkIn.phase === 'verifying' || checkIn.phase === 'preparing'" @change="prepareCheckIn"><el-radio-button v-for="item in today.actions" :key="item.action" :label="item.action" :disabled="!item.allowed">{{ item.label || ACTION_LABELS[item.action] }}</el-radio-button></el-radio-group>
          <p v-for="item in today.actions" :key="`${item.action}-reason`" class="attendance-basis">{{ item.label || ACTION_LABELS[item.action] }}：{{ item.allowed ? '当前可打卡' : item.reason || '当前不可用' }}</p>
        </template>
        <p v-if="checkIn.phase === 'preparing'" role="status">正在准备本次{{ ACTION_LABELS[action] }}…</p><p v-if="checkIn.phase === 'ready'" role="status">{{ ACTION_LABELS[action] }}会话限时两分钟，请定位完成后拍照。</p>
        <CameraCapture ref="camera" :active="dialogFaceFormVisible && ['ready', 'verifying', 'processing', 'uncertain'].includes(checkIn.phase)" :disabled="checkIn.phase !== 'ready' || !address" @captured="verifyCapture" />
        <p v-if="checkIn.phase === 'verifying'" role="status" aria-live="polite">正在验证并保存本次{{ ACTION_LABELS[action] }}，请稍候…</p>
        <el-alert v-if="checkIn.error" :title="checkIn.error" type="error" :closable="false" show-icon />
        <el-button v-if="['uncertain', 'processing'].includes(checkIn.phase)" type="primary" @click="retryAttempt">重试本次验证</el-button><el-button v-if="checkIn.phase === 'failed'" @click="loadToday">重新核对可用动作</el-button>
        <div class="checkin-location"><p v-if="locating" role="status">正在获取当前位置…</p><p v-if="address">当前位置：{{ address }}</p><el-alert v-if="locationError" :title="locationError" type="warning" :closable="false" show-icon /><el-button :loading="locating" :disabled="checkIn.phase === 'verifying' || !!checkIn.attempt" @click="locate">重新定位</el-button><div id="checkin-map" class="checkin-map" :hidden="!address" aria-label="当前位置地图"></div></div>
      </div><template #footer><el-button :disabled="checkIn.phase === 'verifying'" @click="dialogFaceFormVisible = false">关闭并核对事件</el-button></template>
    </el-dialog>
  </main>
</template>
<style scoped>
.checkin-content {display:grid;gap:16px;min-width:0;font-size:16px}
.checkin-content p {margin:0;line-height:1.6;overflow-wrap:anywhere}
.checkin-location {display:grid;gap:12px;padding-top:8px}
.checkin-location .el-button {justify-self:start}
.checkin-map {height:220px;width:100%}
</style>
