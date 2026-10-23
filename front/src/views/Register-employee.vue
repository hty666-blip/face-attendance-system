<script setup>
import { reactive, ref} from "vue"
import { User, Lock, Message } from '@element-plus/icons-vue'
import request from "@/utils/request";
import {ElMessage} from "element-plus";
import router from "@/router";
import config from "../../config";

  const loginData = reactive({})
  const rules = reactive({
    username: [
      { required: true, message: '请输入登录账户', trigger: 'blur' },
    ],
    email: [
      { required: true, message: '请输入邮箱', trigger: 'blur' },
      { type: 'email', message: '请输入正确的邮箱格式', trigger: ['blur', 'change'] }
    ],
    password: [
      { required: true, message: '请输入密码', trigger: 'blur' },
      { min: 8, max: 64, message: '密码长度在8-64位之间', trigger: 'blur' },
    ],
    confirmPassword: [
      { required: true, message: '请确认密码', trigger: 'blur' },
      { min: 8, max: 64, message: '密码长度在8-64位之间', trigger: 'blur' },
    ],
  })
  const ruleFormRef = ref()
  const register = () => {
    ruleFormRef.value.validate(valid => {
      if (valid) {
        if (loginData.password !== loginData.confirmPassword) {
          ElMessage.warning('两次密码不一致')
          return;
        }
        loginData.role = 'employee'
        // 发送表单数据给后台
        request.post('/register', loginData).then(res => {
          if (res.code === '200') {
            ElMessage.success('注册成功')
            router.push('/login')
          } else {
            ElMessage.error(res.msg)
          }
        })
      }
    })
  }

</script>

<template>
  <div class="wrapper">
    <div style="margin: 150px auto; width: 350px; height: 420px; padding: 20px;background-color: rgba(57, 99, 134, 0.75);">
      <div style="margin: 20px 0; text-align: center; font-size: 24px;color: #ffffff;"><b>{{config.projectName}}</b></div>
      <el-form
              ref="ruleFormRef"
              :model="loginData"
              :rules="rules"
              size="large"
              status-icon
      >
        <el-form-item prop="username">
          <el-input size="medium" v-model="loginData.username" placeholder="请输入登录账户"  />
        </el-form-item>
        <el-form-item prop="email">
          <el-input size="medium" v-model="loginData.email" placeholder="请输入邮箱"  />
        </el-form-item>
        <el-form-item prop="password">
          <el-input size="medium" v-model="loginData.password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input size="medium" v-model="loginData.confirmPassword" show-password placeholder="请确认密码"/>
        </el-form-item>
        <el-form-item style="margin: 10px 0; text-align: right">
          <el-button type="primary" size="medium" style="width: 100%" autocomplete="off" @click="register">注册</el-button>
        </el-form-item>
      </el-form>
        <div style="text-align: center;">
          <el-button type="text" autocomplete="off" @click="router.push('/login')">前往登录</el-button>
        </div>
    </div>
  </div>
</template>
<style>
  .wrapper {
    background-image: url("../assets/15865f934ab758621710830763.jpg");
    background-repeat: no-repeat;
    background-size: cover;
    height: 100vh;
    overflow: hidden;
  }
</style>
