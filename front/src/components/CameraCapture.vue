<script setup>
import {ref, watch, onMounted, onBeforeUnmount} from 'vue'
import {CameraController} from '@/utils/camera.mjs'
const props = defineProps({active: Boolean, count: {type: Number, default: 1}, disabled: Boolean})
const emit = defineEmits(['captured', 'cleared'])
const video = ref(), canvas = ref(), samples = ref([]), phase = ref('idle'), error = ref('')
let camera
const reset = () => {samples.value = []; error.value = ''; emit('cleared')}
const start = () => {reset(); camera?.start()}
const take = () => {
  if (props.disabled || phase.value !== 'ready' || samples.value.length >= props.count) return
  try {
    samples.value.push(camera.capture())
    if (samples.value.length === props.count) emit('captured', [...samples.value])
  } catch (reason) {error.value = reason.message}
}
onMounted(() => {
  camera = new CameraController({
    getMedia: constraints => {
      if (!navigator.mediaDevices?.getUserMedia) {const error = new Error(); error.name = 'SecurityError'; throw error}
      return navigator.mediaDevices.getUserMedia(constraints)
    },
    video: video.value, canvas: canvas.value,
    onState: (value, message) => {phase.value = value; error.value = message},
  })
  if (props.active) start()
})
watch(() => props.active, active => {if (active) start(); else {camera?.stop(); reset()}})
onBeforeUnmount(() => {camera?.stop(); reset()})
defineExpose({reset})
</script>
<template>
  <section class="camera-capture" aria-label="人脸拍摄">
    <div class="camera-capture__stage">
      <video ref="video" autoplay muted playsinline aria-label="摄像头实时画面"></video>
      <p v-if="phase === 'opening'" class="camera-capture__placeholder" role="status">正在启动摄像头，请允许浏览器访问。</p>
      <p v-if="phase === 'idle'" class="camera-capture__placeholder">摄像头尚未开启</p>
    </div>
    <canvas ref="canvas" hidden></canvas>
    <p class="camera-capture__hint">请本人单独入镜，正对摄像头，保持光线均匀、画面清晰。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" />
    <div v-if="count > 1" class="camera-capture__samples" aria-label="本次采集的照片">
      <figure v-for="(sample, index) in samples" :key="index">
        <img :src="sample" :alt="`第${index + 1}张采集照片`" />
        <figcaption>第 {{ index + 1 }} 张</figcaption>
      </figure>
      <span role="status">已采集 {{ samples.length }} / {{ count }} 张</span>
    </div>
    <div class="camera-capture__actions">
      <el-button v-if="phase === 'error'" @click="start">重试摄像头</el-button>
      <el-button v-else type="primary" :disabled="disabled || phase !== 'ready' || samples.length >= count" @click="take">
        {{ count > 1 ? `拍摄第 ${Math.min(samples.length + 1, count)} 张` : '拍照并验证' }}
      </el-button>
      <el-button v-if="samples.length" :disabled="disabled" @click="reset">重新拍摄</el-button>
    </div>
  </section>
</template>
<style scoped>
.camera-capture {display:grid;gap:16px;min-width:0;font-size:16px}
.camera-capture__stage {position:relative;aspect-ratio:4/3;background:#18212a;overflow:hidden;border-radius:8px}
.camera-capture__stage video {display:block;width:100%;height:100%;object-fit:contain;transform:scaleX(-1)}
.camera-capture__placeholder {position:absolute;inset:0;display:grid;place-content:center;padding:24px;text-align:center;color:#fff}
.camera-capture__hint {margin:0;color:#46515d;line-height:1.6}
.camera-capture__actions {display:flex;gap:12px;flex-wrap:wrap}
.camera-capture__actions .el-button {margin:0}
.camera-capture__samples {display:flex;gap:12px;align-items:center;flex-wrap:wrap;color:#46515d}
.camera-capture__samples figure {margin:0;text-align:center;font-size:14px}
.camera-capture__samples img {display:block;width:72px;height:54px;object-fit:cover;margin-bottom:4px;border-radius:4px}
</style>
