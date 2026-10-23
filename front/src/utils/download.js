import request from './request'
import {ElMessage} from 'element-plus'

export async function downloadExport(path) {
  try {
    const blob = await request.get(path, {responseType: 'blob'})
    if (blob.type.includes('json')) {
      const result = JSON.parse(await blob.text())
      ElMessage.error(result.msg || '导出失败')
      return
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${path.split('/')[1]}-${new Date().toISOString().slice(0,10)}.xlsx`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    ElMessage.error('导出失败，请检查登录状态后重试')
  }
}

// 只向配置的业务后端发送鉴权头，URL原始域名不参与请求。
export async function protectedImage(url) {
  if (!url) return ''
  const match = String(url).match(/(?:^|\/)file\/download\/([a-f0-9]{32}\.(?:png|jpg|jpeg))$/)
  if (!match) return ''
  const blob = await request.get(`/file/download/${match[1]}`, {responseType: 'blob'})
  return blob.type.startsWith('image/') ? URL.createObjectURL(blob) : ''
}
