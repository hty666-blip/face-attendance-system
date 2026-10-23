<script setup>
import {ref, watch, onBeforeUnmount} from 'vue'
import {protectedImage} from '@/utils/download'
const props = defineProps({src: String})
const image = ref('')
let version = 0
const clear = () => {if (image.value) URL.revokeObjectURL(image.value); image.value = ''}
watch(() => props.src, async value => {
  const current = ++version
  clear()
  try {
    const result = await protectedImage(value)
    if (current !== version) {if (result) URL.revokeObjectURL(result); return}
    image.value = result
  } catch { /* 未授权或文件不存在时显示占位。 */ }
}, {immediate: true})
onBeforeUnmount(() => {version++; clear()})
</script>
<template><img v-if="image" :src="image" alt="头像" /><span v-else class="image-placeholder">头像</span></template>
