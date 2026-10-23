<script setup>
import {ref, watch} from 'vue'
import {ElMessage} from 'element-plus'
import CameraCapture from './CameraCapture.vue'
import request from '@/utils/request'
import '@/assets/css/face-dialog.css'
const props = defineProps({modelValue: Boolean, userId: Number, name: String})
const emit = defineEmits(['update:modelValue', 'updated'])
const status = ref(null), ready = ref(false), error = ref(''), busy = ref(false), loading = ref(false), images = ref([]), camera = ref()
let generation = 0
const load = async () => {
  const current = ++generation
  error.value = ''; status.value = null; ready.value = false; images.value = []; loading.value = true
  try {
    const [result, service] = await Promise.all([request.get('/face/status', {params: {userId: props.userId}}), request.get('/face/service-status')])
    if (current !== generation || !props.modelValue) return
    if (result.code !== '200' || service.code !== '200') throw new Error(result.msg || service.msg)
    status.value = result.data; ready.value = service.data.ready
    if (!ready.value) error.value = '识别服务尚未就绪，请检查本地服务和模型配置后重试。'
  } catch (reason) {if (current === generation) error.value = reason.message || '无法读取人脸状态，请重试。'}
  finally {if (current === generation) loading.value = false}
}
watch(() => [props.modelValue, props.userId], ([open]) => {if (open) load(); else {generation++; images.value = []; ready.value = false; loading.value = false}}, {immediate: true})
const perform = async (path, data) => {
  busy.value = true; error.value = ''
  try {
    const result = await request.post(path, data)
    if (result.code !== '200') throw new Error(result.msg)
    ElMessage.success(path.endsWith('revoke') ? '人脸已撤销' : '人脸录入成功')
    camera.value?.reset(); images.value = []; await load(); emit('updated')
  } catch (reason) {error.value = reason.message || '操作失败，请检查网络后重试。'}
  finally {busy.value = false}
}
const close = () => {if (!busy.value) emit('update:modelValue', false)}
</script>
<template>
  <el-dialog :model-value="modelValue" :title="`${name || '员工'}的人脸管理`" width="min(680px, 94vw)" top="5vh" class="face-dialog" append-to-body destroy-on-close
             :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="close">
    <div class="face-enrollment">
      <p v-if="status" role="status">当前状态：{{ status.enrolled ? '已录入' : '未录入' }}<span v-if="status.enrolled">，模板版本 {{ status.version }}</span></p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <p v-if="loading" role="status">正在读取人脸状态…</p>
      <el-button v-if="!status || !ready" :loading="loading" :disabled="busy" @click="load">重新检查服务</el-button>
      <CameraCapture ref="camera" :active="modelValue && ready" :count="3" :disabled="busy" @captured="images = $event" @cleared="images = []" />
      <p class="face-enrollment__note">请确认员工同意采集。本地模式只保存加密特征，三张照片不会作为打卡照片长期保存。</p>
      <el-button type="primary" :loading="busy" :disabled="images.length !== 3 || !ready" @click="perform('/face/enroll', {userId, images})">保存三张采集结果</el-button>
      <div class="face-enrollment__maintenance">
        <el-popconfirm v-if="status?.legacyAvailable" title="迁移成功后会清除该员工旧照片，保留加密模板。请确认已备份旧库。" @confirm="perform('/face/migrate', {userId})">
          <template #reference><el-button :disabled="busy || !ready">迁移旧照片</el-button></template>
        </el-popconfirm>
        <el-popconfirm v-if="status?.revocable" title="将清除该账号全部识别方式的人脸模板和旧照片，未完成的打卡会话失效。确认撤销？" @confirm="perform('/face/revoke', {userId})">
          <template #reference><el-button type="danger" plain :disabled="busy">撤销人脸</el-button></template>
        </el-popconfirm>
      </div>
    </div>
    <template #footer><el-button :disabled="busy" @click="close">关闭</el-button></template>
  </el-dialog>
</template>
<style scoped>
.face-enrollment {display:grid;gap:16px;min-width:0;font-size:16px}
.face-enrollment p {margin:0;line-height:1.6}
.face-enrollment__note {color:#46515d}
.face-enrollment__maintenance {display:flex;gap:12px;flex-wrap:wrap;padding-top:8px}
.face-enrollment__maintenance .el-button {margin:0}
</style>
