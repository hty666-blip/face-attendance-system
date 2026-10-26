import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {runInNewContext} from 'node:vm'
import asyncValidator from 'async-validator'

test('登录表单只校验密码非空，保留既有口令且不套用新密码长度政策', async () => {
  const source = readFileSync(new URL('../src/views/Login.vue', import.meta.url), 'utf8')
  const passwordRules = source.match(/password:\s*(\[[\s\S]*?\])/)
  assert.ok(passwordRules, '登录表单必须保留密码校验规则')
  assert.match(source, /:rules="rules"/)
  assert.match(source, /v-model="loginData\.password"/)
  const rules = runInNewContext(`(${passwordRules[1]})`, Object.create(null), {timeout: 100})
  const Schema = asyncValidator.default || asyncValidator
  const validator = new Schema({password: rules})
  for (const password of ['a', 'abc', 'x'.repeat(32), 'x'.repeat(44), 'x'.repeat(64), 'x'.repeat(96)]) await validator.validate({password})
  await assert.rejects(() => validator.validate({password: ''}))
})
