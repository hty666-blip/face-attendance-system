export class CameraController {
  constructor({getMedia, video, canvas, onState = () => {}}) {
    this.getMedia = getMedia; this.video = video; this.canvas = canvas; this.onState = onState
    this.generation = 0; this.stream = null; this.ready = false
  }
  stop() {
    this.generation++; this.ready = false
    this.stream?.getTracks().forEach(track => track.stop())
    this.stream = null
    if (this.video) {this.video.pause(); this.video.srcObject = null}
    this.onState('idle', '')
  }
  async start() {
    this.stop()
    const generation = this.generation
    this.onState('opening', '')
    try {
      const stream = await this.getMedia({audio: false, video: {width: {ideal: 640}, height: {ideal: 480}, facingMode: 'user'}})
      if (generation !== this.generation) {stream.getTracks().forEach(track => track.stop()); return}
      this.stream = stream; this.video.srcObject = stream
      await this.video.play()
      if (generation !== this.generation) return
      this.ready = true; this.onState('ready', '')
    } catch (error) {
      if (generation !== this.generation) return
      this.stop()
      const messages = {
        NotAllowedError: '摄像头权限被拒绝，请在浏览器地址栏允许使用摄像头后重试。',
        NotFoundError: '没有找到摄像头，请连接设备后重试。',
        NotReadableError: '摄像头可能正被其他应用占用，请关闭其他应用后重试。',
        SecurityError: '此页面无法访问摄像头，请使用本机 localhost 或 HTTPS。',
      }
      this.onState('error', messages[error.name] || '摄像头未能启动，请检查设备和浏览器权限后重试。')
    }
  }
  capture() {
    if (!this.ready || !this.stream || !this.video.videoWidth) throw new Error('摄像头尚未准备好，请稍候再拍摄。')
    const scale = Math.min(1, 1280 / this.video.videoWidth)
    this.canvas.width = Math.round(this.video.videoWidth * scale)
    this.canvas.height = Math.round(this.video.videoHeight * scale)
    this.canvas.getContext('2d').drawImage(this.video, 0, 0, this.canvas.width, this.canvas.height)
    return this.canvas.toDataURL('image/jpeg', 0.9)
  }
}
