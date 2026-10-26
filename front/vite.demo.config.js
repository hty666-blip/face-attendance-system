import {fileURLToPath} from 'node:url'
import {basename, dirname, isAbsolute, resolve} from 'node:path'
import {lstatSync, readdirSync, realpathSync} from 'node:fs'
import {mergeConfig} from 'vite'
import baseConfig from './vite.config.js'

const frontRoot = fileURLToPath(new URL('.', import.meta.url))
const demoRoot = resolve(frontRoot, '../.local/demo')
const samePath = (first, second) => process.platform === 'win32' ? first.toLowerCase() === second.toLowerCase() : first === second
const envDirInput = process.env.ATTENDANCE_DEMO_ENV_DIR
if (!envDirInput || !isAbsolute(envDirInput)) throw new Error('ATTENDANCE_DEMO_ENV_DIR 必须是本轮演示运行目录的绝对路径。')
const envDir = resolve(envDirInput)
const runDir = dirname(envDir)
if (basename(envDir) !== 'vite-env' || !samePath(dirname(runDir), demoRoot) || !/^[a-f0-9]{32}$/.test(basename(runDir))) throw new Error('演示环境目录必须位于本项目 .local/demo/<32位runId>/vite-env。')
for (const directory of [resolve(frontRoot, '..'), dirname(demoRoot), demoRoot, runDir, envDir]) {
  const entry = lstatSync(directory)
  if (!entry.isDirectory() || entry.isSymbolicLink() || !samePath(realpathSync.native(directory), directory)) throw new Error('演示环境目录必须逐级为已创建的普通目录，不允许重解析路径。')
}
if (readdirSync(envDir).length) throw new Error('演示 vite-env 目录必须为空，不允许读取已有.env配置；请启动新一轮演示。')

const validPort = value => typeof value === 'string' && /^[1-9]\d{0,4}$/.test(value) && Number(value) <= 65535
const portInput = process.env.VITE_DEMO_PORT, serverUrl = process.env.VITE_SERVER_URL
if (!validPort(portInput)) throw new Error('VITE_DEMO_PORT 必须由演示启动器提供有效端口。')
const serverMatch = typeof serverUrl === 'string' && /^127\.0\.0\.1:([1-9]\d{0,4})$/.exec(serverUrl)
if (!serverMatch || !validPort(serverMatch[1])) throw new Error('演示 VITE_SERVER_URL 只允许 127.0.0.1:<有效端口>。')

export default mergeConfig(baseConfig, {
  root: frontRoot,
  envDir,
  // 演示只公开此处验证的服务地址；不向浏览器转发继承环境或 .env 中的其他 VITE_*。
  envPrefix: [],
  define: {
    'import.meta.env.VITE_SERVER_URL': JSON.stringify(serverUrl),
    'import.meta.env.VITE_AMAP_KEY': JSON.stringify(''),
    'import.meta.env.VITE_AMAP_SECURITY_CODE': JSON.stringify('')
  },
  server: {host: '127.0.0.1', port: Number(portInput), strictPort: true, open: false, https: false, cors: false}
})
