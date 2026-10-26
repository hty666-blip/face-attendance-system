<script setup>
const videoWidth = 200;
const videoHeight = 200;
import {onMounted, reactive, ref, watch} from "vue"
import {User, Lock} from '@element-plus/icons-vue'
import request from "@/utils/request";
import {ElMessage} from "element-plus";
import {useUserStore} from "@/stores/user";
import router, {setRoutes} from "@/router";
import SIdentify from '../components/SIdentify.vue';
import config from "../../config";

// 图形验证码
let identifyCodes = "1234567890"
let identifyCode = ref('')
const failCount = ref(0)
const randomNum = (min, max) => {
  return Math.floor(Math.random() * (max - min) + min)
}
const makeCode = (o, l) => {
  for (let i = 0; i < l; i++) {
    identifyCode.value += o[randomNum(0, o.length)];
  }
}
const refreshCode = () => {
  identifyCode.value = "";
  makeCode(identifyCodes, 4);
}
// 生成验证码
onMounted(() => {
  identifyCode.value = "";
  makeCode(identifyCodes, 4);
})

const loginData = reactive({})
const rules = reactive({
  username: [
    {required: true, message: '请输入账号', trigger: 'blur'},
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
  ],
})
const ruleFormRef = ref()
const login = () => {
  ruleFormRef.value.validate(valid => {
    if (valid) {
      // 失败3次触发验证码
      if (failCount.value >= 3 && loginData.code !== identifyCode.value) {
        ElMessage.warning('验证码错误')
        return
      }
      // 发送表单数据给后台
      request.post('/login', loginData).then(res => {
        if (res.code === '200') {
          if (res.data.user.role === 'ADMIN') {
            router.push('/') // 后台的首页
          }
                  else if(res.data.user.role === 'manager'){
            router.push('/')
          }
          else if(res.data.user.role === 'employee'){
            router.push('/')
          }
          else{
            router.push('/')
          }
          ElMessage.success('登录成功')
          const userStore = useUserStore()
          userStore.setManagerInfo(res.data)
        } else {
          ElMessage.error(res.msg)
          failCount.value ++  // 失败次数加1
        }
      })
    }
  })
}

</script>

<template>
  <div class="wrapper">
  <div class="Box">
    <div class="left">
      <div class="centerBox">
        <h4>{{config.projectName}}</h4>
      </div>
    </div>
    <div class="right">
      <el-form
              ref="ruleFormRef"
              :model="loginData"
              :rules="rules"
              size="large"
              status-icon
      >
        <h3>欢迎登录</h3>
        <el-form-item prop="username">
          <el-input size="medium" prefix-icon="User" v-model="loginData.username" placeholder="请输入登录账号"></el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input size="medium" prefix-icon="Lock" show-password v-model="loginData.password" placeholder="请输入密码"></el-input>
        </el-form-item>
        <el-form-item v-if="failCount >= 3">
          <el-input v-model="loginData.code" placeholder="请输入图形验证码" style="width:55%" />
          <SIdentify :identifyCode="identifyCode" @click="refreshCode" />
        </el-form-item>
        <el-form-item style="margin: 10px 0;">
          <el-button type="primary" size="medium" style="width: 100%" autocomplete="off" @click="login">登录</el-button>
        </el-form-item>
        <p class="no">没有账号？</p>
      </el-form>
    </div>

  </div>
  </div>
</template>
<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
  }

  .wrapper {
    height: 100vh;
    background-color: aquamarine;
    background-image: url("../assets/15865f934ab758621710830763.jpg");
    background-size: cover;
  }

  .Box {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    width: 550px;
    height: 330px;
    display: flex;
  }

  .left {
    position: relative;
    width: 50%;
    height: 100%;
    background-color: rgba(57, 99, 134, 0.75);
  }

  .right {
    position: relative;
    width: 50%;
    height: 100%;
    background-color: rgba(255, 255, 255, 1);
  }

  .centerBox {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    width: 80%;
    text-align: center;
  }

  .left img {
    width: 50px;
    height: 50px;
    margin-bottom: 5px;
  }

  .left h4 {
    font-size: 22px;
    color: #fff;
  }

  .right form {
    position: absolute;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    width: 80%;
    text-align: center;
  }

  h3 {
    margin-bottom: 20px;
  }

  .loginBtn {
    width: 100%;
    height: 35px;
    line-height: 32px;
    text-align: center;
    font-size: 15px;
    color: #fff;
    border-radius: 6px;
    background: rgb(57, 99, 134);
    outline: none;
    border: none;
    margin-top: 10px;
  }

  .no {
    cursor: pointer;
    margin-top: 30px;
    font-size: 12px;
    color: #828282;
  }

  .reg {
    cursor: pointer;
    font-size: 12px;
    color: #828282;
  }

  .login{
    width: 100%;
    height: 100%;
    position: absolute;
    left: 0px;
    top:0px;
    background-size: cover;
    filter: blur(10px);

  }
  .login-mid{
    position: absolute;
    width: 45%;
    height: 65%;
    background-color: rgba(37, 76, 102,0.5);
    transform: translate(-50%,-50%);
    left: 50%;
    top: 50%;
    cursor: pointer;
  }
  .login-mid-top,.login-mid-bot{
    width: 100%;
    height: 10%;
  }
  .shadow-top-left{
    float: left;
    width: 50px;
    height: 100%;
    border-top: 2px solid #00ffff;
    border-left: 2px solid #00ffff;
    filter: blur(2px);
  }
  .shadow-top-right{
    float: right;
    width: 50px;
    height: 100%;
    border-top: 2px solid #00ffff;
    border-right: 2px solid #00ffff;
    filter: blur(2px);
  }
  .shadow-bot-left{
    float: left;
    width: 50px;
    height: 100%;
    border-bottom: 2px solid #00ffff;
    border-left: 2px solid #00ffff;
    filter: blur(2px);
  }
  .shadow-bot-right{
    float: right;
    width: 50px;
    height: 100%;
    border-bottom: 2px solid #00ffff;
    border-right: 2px solid #00ffff;
    filter: blur(2px);
  }
  .login-mid-mid{
    width: 100%;
    height: 80%;
  }
  .login-mid:hover .shadow-top-left,
  .login-mid:hover .shadow-top-right,
  .login-mid:hover .shadow-bot-left,
  .login-mid:hover .shadow-bot-right{
    border-color: #fff;
    filter: blur(1px);
  }
  .videoCamera-canvasCamera{
    position: relative;
    width: 200px;
    height: 200px;
    margin: 0 auto;
  }
  .face-special-effects,.face-special-effects-2{
    width:  300px;
    height: 300px;
    position: absolute;
    z-index: 99999999999!important;
    transform: translate(-50%,-50%);
    top: 50%;
    left: 50%;
  }
  .face-special-effects{
    background: none no-repeat;
    background-size: 300px 300px;
  }
  .face-special-effects-2{
    background: none no-repeat;
    background-size: 300px 300px;
  }
  .face-btn{
    width: 100%;
    height: 50px;
    z-index: 999999999999!important;
    margin-top: 45px;
    text-align: center;
  }
  .face-btn button{
    border: none;
    outline: none;
    width: 200px;
    height: 40px;
    border-radius: 6px;
    color: #ffffff;
    cursor: pointer;
  }
  .msg{
    width: 100%;
    height: 75px;
    color: #ffffff;
  }
  .server-msg{
    width: 100%;
    height: 35px;
    text-align: center;
    line-height: 35px;
  }
  .welcome{
    width: 100%;
    height: 40px;
    text-align: center;
    line-height: 40px;
    font-size: 20px;
    font-family: "Baskerville Old Face";
    color: white;
  }
</style>
