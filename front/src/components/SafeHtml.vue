<script setup>
import {ref, watch, onBeforeUnmount} from 'vue'
import {protectedImage} from '@/utils/download'
const props = defineProps({html: String})
const content = ref('')
let urls = [], version = 0
const clear = () => {urls.forEach(URL.revokeObjectURL); urls = []}
const allowed = new Set(['P','BR','B','STRONG','I','EM','U','S','UL','OL','LI','BLOCKQUOTE','H1','H2','H3','H4','PRE','CODE','A','IMG','SPAN','DIV'])
watch(() => props.html, async html => {
  const current = ++version
  clear(); content.value = ''
  const doc = new DOMParser().parseFromString(html || '', 'text/html')
  for (const node of [...doc.body.querySelectorAll('*')]) {
    if (!allowed.has(node.tagName)) {node.remove(); continue}
    const src = node.getAttribute('src'), href = node.getAttribute('href')
    for (const attr of [...node.attributes]) node.removeAttribute(attr.name)
    if (node.tagName === 'A' && /^https?:\/\//i.test(href || '')) {
      node.setAttribute('href', href); node.setAttribute('rel', 'noopener noreferrer')
    }
    if (node.tagName === 'IMG') {
      try {
        const url = await protectedImage(src)
        if (current !== version) {if (url) URL.revokeObjectURL(url); return}
        if (url) {urls.push(url); node.setAttribute('src', url)} else node.remove()
      } catch {node.remove()}
    }
  }
  if (current === version) content.value = doc.body.innerHTML
}, {immediate: true})
onBeforeUnmount(() => {version++; clear()})
</script>
<template><div v-html="content"></div></template>
