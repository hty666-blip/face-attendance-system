<script setup>
import ProtectedImage from "@/components/ProtectedImage.vue";
const videoWidth = 200;
const videoHeight = 200;
const faceImgState = false;
import {reactive, defineEmits, ref, watch} from "vue";
import request from "@/utils/request";
import {ElMessage} from "element-plus";
import config from "../../config";
import {useUserStore} from "@/stores/user";

const userStore = useUserStore()
const user = userStore.getUser
const token = userStore.getBearerToken

const state = reactive({
  form: {}
})
const load = () => {
  request.get('/user/' + user.id).then(res => {
    state.form = res.data
  })
}
load()

const handleImportSuccess = (res) => {
  state.form.avatar = res.data
  ElMessage.success("上传头像成功")
}

let $myEmit = defineEmits(['getAvatar'])
const save = () => {
  request.put("/updateUser", state.form).then(res => {
    if (res.code === '200') {
      ElMessage.success('更新成功')
      userStore.setUser(res.data)
      $myEmit('getAvatar', res.data.avatar)
    } else {
      ElMessage.error(res.msg)
    }
  })
}

const dialogFormVisible = ref(false)
state.faceOption ={};
state.cameraState = false
state.faceObj = {
  id:null,
  face:'',
}
//获取摄像头权限
const getCamera =(option) =>{
  option.thisCancas = document.getElementById(option.canvasId);
  option.thisContext = option.thisCancas.getContext("2d");
  option.thisVideo = document.getElementById(option.videoId);
  option.thisVideo.style.display = "block";
  // 获取媒体属性，旧版本浏览器可能不支持mediaDevices，我们首先设置一个空对象
  if (navigator.mediaDevices === undefined) {
    navigator.mediaDevices = {};
  }
  // 一些浏览器实现了部分mediaDevices，我们不能只分配一个对象
  // 使用getUserMedia，因为它会覆盖现有的属性。
  // 这里，如果缺少getUserMedia属性，就添加它。
  if (navigator.mediaDevices.getUserMedia === undefined) {
    navigator.mediaDevices.getUserMedia = function (constraints) {
      // 首先获取现存的getUserMedia(如果存在)
      var getUserMedia =
          navigator.webkitGetUserMedia ||
          navigator.mozGetUserMedia ||
          navigator.getUserMedia;
      // 有些浏览器不支持，会返回错误信息
      // 保持接口一致
      if (!getUserMedia) {
        //不存在则报错
        return Promise.reject(
            new Error("getUserMedia is not implemented in this browser")
        );
      }
      // 否则，使用Promise将调用包装到旧的navigator.getUserMedia
      return new Promise(function (resolve, reject) {
        getUserMedia.call(navigator, constraints, resolve, reject);
      });
    };
  }
  var constraints = {
    audio: false,
    video: {
      width: option.videoWidth,
      height: option.videoHeight,
      transform: "scaleX(-1)",
    },
  };
  navigator.mediaDevices
      .getUserMedia(constraints)
      .then(function (stream) {
        // 旧的浏览器可能没有srcObject
        if ("srcObject" in option.thisVideo) {
          option.thisVideo.srcObject = stream;
        } else {
          // 避免在新的浏览器中使用它，因为它正在被弃用。
          option.thisVideo.src = window.URL.createObjectURL(stream);
        }
        option.thisVideo.onloadedmetadata = function (e) {
          option.thisVideo.play();
        };
      })
      .catch((err) => {
        console.log(err);
      });
  return option
}

/**
 * 绘制图片
 * 返回格式为base64
 * @param option
 */
const draw = (option) =>{
  option.thisContext.drawImage(option.thisVideo,0,0,200,200);
  return option.thisCancas.toDataURL("image/png");
}

const updateFace = () =>{
  dialogFormVisible.value = true
  setTimeout(()=>{
    state.cameraState = true
  },1500)
}

// 监听弹出层 设置摄像头是否开启
watch(() => dialogFormVisible.value, (newValue, oldValue) => {
  if (newValue){
    setTimeout(()=>{
      // 开启摄像头
      state.faceOption = getCamera({
        videoWidth: 200,
        videoHeight: 200,
        thisCancas: null,
        thisContext: null,
        thisVideo: null,
        canvasId:'canvasCamera2',
        videoId:'videoCamera2'
      });
    },1000)
  } else {
    let faceBase = document.getElementById("faceBase");
    faceBase.innerHTML= ""
    if(state.faceOption.thisVideo != null){
      // 不等于null 关闭摄像头
      state.faceOption.thisVideo?.srcObject?.getTracks().forEach(track => track.stop());
    }
  }
})

//录入人脸
const enteringFace =()=> {
  state.faceObj.face = draw(state.faceOption)
  let faceBase = document.getElementById("faceBase");
  faceBase.innerHTML = "<img src='"+state.faceObj.face+"' />"
}

//更新人脸数据
const confirmUpdateFace =() =>{
  state.faceObj.id = user.id
  request.post("/updateFace", state.faceObj).then(res => {
    if (res.code === '200') {
      ElMessage.success('人脸数据更新成功')
      dialogFormVisible.value = false
    } else {
      ElMessage.error(res.msg)
    }
  })
}
</script>

<template>
  <div style="width: 40%;margin-top: 30px;">
    <span style="font-size: 22px;text-align: center;line-height: 80px;"><h3>修改个人信息</h3></span>
    <el-form style="width: 80%; margin: 0 auto" label-width="60px">
      <div style="text-align: center">
        <el-upload
                class="avatar-uploader"
                :show-file-list="false"
                :action='`http://${config.serverUrl}/file/upload`'
                :on-success="handleImportSuccess"
                :headers="{ Authorization: token}"
        >
          <ProtectedImage v-if="state.form.avatar" :src="state.form.avatar" class="avatar" />
          <el-icon v-else class="avatar-uploader-icon"><Plus /></el-icon>
        </el-upload>
      </div>

      <el-form-item label="修改账户" style="margin-top: 20px;" label-width="250px;">
        <el-input v-model="state.form.username" disabled></el-input>
      </el-form-item>
      <el-form-item label="修改Email" label-width="250px;">
        <el-input v-model="state.form.email"></el-input>
      </el-form-item>
    </el-form>
    <div style="text-align: center; width: 100%">
      <el-button type="primary" @click="save">确认修改</el-button>
    </div>

    <!-- 人脸识别区域-->
    <el-dialog v-model="dialogFormVisible" width="40%">
      <el-form :rules="rules" ref="ruleForm" label-width="80px">

        <el-form-item label="人脸数据" class="login-mid-mid" prop="faceBase">
          <!--捕获人脸区域-->
          <div class="savevideoCamera-canvasCamera ">
            <video
                id="videoCamera2"
                :width="videoWidth"
                :height="videoHeight"
                autoplay
            ></video>
            <canvas
                style="display: none"
                id="canvasCamera2"
                :width="videoWidth"
                :height="videoHeight"
            ></canvas>

            <!--人脸特效区域-->
            <div v-if="state.faceImgState" class="saveface-special-effects-2"></div>
            <div v-else class="saveface-special-effects"></div>
            <el-input type="hidden" ></el-input>
          </div>

          <!--拍照显示区域-->
          <div class="savefaceBase" id="faceBase">

          </div>

          <!--按钮区域-->
          <div class="saveface-btn" v-if="state.cameraState">
            <el-button @click="enteringFace">录入人脸</el-button>
          </div>

        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="confirmUpdateFace">确认成功</el-button>
        </el-form-item>

      </el-form>
    </el-dialog>

  </div>
</template>

<style>
  .avatar-uploader .avatar {
    width: 200px;
    height: 200px;
    display: block;
  }
  .avatar-uploader .el-upload {
    border: 1px dashed #ccc;
    cursor: pointer;
    position: relative;
    overflow: hidden;
  }

  .avatar-uploader .el-upload:hover {
    border-color: #ccc;
  }

  .el-icon.avatar-uploader-icon {
    font-size: 28px;
    color: #8c939d;
    width: 200px;
    height: 200px;
    text-align: center;
  }

.page-row{
  margin-top: 20px;
  text-align: center;
}
.savevideoCamera-canvasCamera{
  position: relative;
  width: 200px;
  height: 200px;
}
.saveface-special-effects,.saveface-special-effects-2{
  width:  300px;
  height: 300px;
  position: absolute;
  z-index: 99999999999!important;
  transform: translate(-50%,-50%);
  top: 50%;
  left: 0;
  margin-left: 100px;
}
.saveface-special-effects{
  background: none no-repeat;
  background-size: 300px 300px;
}
.saveface-special-effects-2{
  background: none no-repeat;
  background-size: 300px 300px;
}
.saveface-btn{
  width: 100%;
  height: 50px;
  z-index: 999999999999!important;
  margin-top: 45px;
  text-align: left;
}
.saveface-btn button{
  background-color: #ffffff;
  border: 1px solid #dcdfe6;
  width: 200px;
  height: 40px;
  color: #333333;
  cursor: pointer;
}

.savefaceBase{
  width:  200px;
  height: 200px;
  position: absolute;
  z-index: 99999999999!important;
  transform: translate(-50%,-50%);
  top: 34%;
  left: 75%;
  border: 1px solid #dcdfe6;
}
.savefaceBase img{
  width: 100%;
  height: 100%;
}
</style>
