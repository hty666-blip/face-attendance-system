import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import {mkdirSync, rmSync, rmdirSync, symlinkSync, writeFileSync} from 'node:fs'
import {basename, dirname, resolve} from 'node:path'
import {fileURLToPath} from 'node:url'
import {loadConfigFromFile, resolveConfig} from 'vite'

const frontRoot = fileURLToPath(new URL('..', import.meta.url)), demoRoot = resolve(frontRoot, '../.local/demo')
const configEnvironment = {command: 'serve', mode: 'development'}
const baseConfig = (await loadConfigFromFile(configEnvironment, resolve(frontRoot, 'vite.config.js'), frontRoot, 'silent')).config
// 走Vite自己的配置加载器，保持与Node20及实际启动行为一致，不依赖Node隐式识别.js模块。
const importDemo = async () => ({default: (await loadConfigFromFile(configEnvironment, resolve(frontRoot, 'vite.demo.config.js'), frontRoot, 'silent')).config})
const withEnvironment = async (changes, operation) => {
  const original = Object.fromEntries(Object.keys(changes).map(key => [key, process.env[key]]))
  try {for (const [key, value] of Object.entries(changes)) {if (value === undefined) delete process.env[key]; else process.env[key] = value}; await operation()}
  finally {for (const [key, value] of Object.entries(original)) {if (value === undefined) delete process.env[key]; else process.env[key] = value}}
}
const withRunDirectory = async operation => {
  const runDirectory = resolve(demoRoot, randomBytes(16).toString('hex')), directory = resolve(runDirectory, 'vite-env')
  mkdirSync(directory, {recursive: true})
  try {await operation(directory)}
  finally {
    // 只清理本测试刚创建的随机子目录，禁止以父目录或计算出的外部路径清理。
    assert.equal(dirname(runDirectory), demoRoot); assert.match(basename(runDirectory), /^[a-f0-9]{32}$/)
    rmSync(runDirectory, {recursive: true})
  }
}

test('演示配置复用基础别名与Vue插件，仅绑定指定本地端口并隔离环境目录', async () => {
  await withRunDirectory(async directory => withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: '7001', VITE_SERVER_URL: '127.0.0.1:19091', VITE_MODEL_SECRET: 'synthetic-not-a-key'}, async () => {
    const {default: demo} = await importDemo()
    assert.equal(demo.envDir, directory); assert.equal(demo.root, frontRoot)
    assert.equal(demo.server.host, '127.0.0.1'); assert.equal(demo.server.port, 7001); assert.equal(demo.server.strictPort, true)
    assert.equal(demo.resolve.alias['@'], baseConfig.resolve.alias['@']); assert.ok(demo.plugins.length)
    assert.equal(demo.define['import.meta.env.VITE_SERVER_URL'], '"127.0.0.1:19091"')
    const resolved = await resolveConfig({...demo, configFile: false}, 'serve')
    assert.equal(resolved.env.VITE_MODEL_SECRET, undefined); assert.equal(resolved.env.VITE_SERVER_URL, undefined)
    assert.equal(resolved.define['import.meta.env.VITE_SERVER_URL'], '"127.0.0.1:19091"')
    assert.equal(baseConfig.server.host, '0.0.0.0'); assert.equal(baseConfig.server.port, 7000); assert.equal(baseConfig.envDir, undefined)
  }))
})

test('演示配置拒绝缺失、相对或非本项目run目录', async () => {
  for (const directory of [undefined, '.local/demo/relative', frontRoot]) await withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: '7001', VITE_SERVER_URL: '127.0.0.1:19091'}, async () => assert.rejects(importDemo))
})

test('演示服务地址拒绝非本地目标、协议、路径与无效端口', async () => {
  await withRunDirectory(async directory => {
    for (const serverUrl of [undefined, 'localhost:19091', '0.0.0.0:19091', '192.168.1.2:19091', 'example.invalid:19091', 'http://127.0.0.1:19091', '127.0.0.1:19091/path', '127.0.0.1:0', '127.0.0.1:65536']) await withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: '7001', VITE_SERVER_URL: serverUrl}, async () => assert.rejects(importDemo))
  })
})

test('演示前端端口必须由启动器明确提供，不自动换端口或使用默认端口', async () => {
  await withRunDirectory(async directory => {
    for (const port of [undefined, '', '0', '7001.5', '07001', '65536', '7001;command']) await withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: port, VITE_SERVER_URL: '127.0.0.1:19091'}, async () => assert.rejects(importDemo))
  })
})

test('演示配置拒绝已有env文件或重解析目录，不能从其他位置加载配置', async () => {
  await withRunDirectory(async directory => withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: '7001', VITE_SERVER_URL: '127.0.0.1:19091'}, async () => {
    writeFileSync(resolve(directory, '.env.local'), 'VITE_SERVER_URL=example.invalid:9090\n')
    await assert.rejects(importDemo, /必须为空/)
  }))
  await withRunDirectory(async directory => withEnvironment({ATTENDANCE_DEMO_ENV_DIR: directory, VITE_DEMO_PORT: '7001', VITE_SERVER_URL: '127.0.0.1:19091'}, async () => {
    const actualDirectory = resolve(dirname(directory), 'actual-env')
    mkdirSync(actualDirectory)
    assert.equal(basename(directory), 'vite-env'); assert.equal(dirname(dirname(directory)), demoRoot)
    rmdirSync(directory)
    symlinkSync(actualDirectory, directory, process.platform === 'win32' ? 'junction' : 'dir')
    await assert.rejects(importDemo, /重解析路径/)
  }))
})
