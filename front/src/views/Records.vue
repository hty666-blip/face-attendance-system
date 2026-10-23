<script setup>
const videoWidth = 200;
const videoHeight = 200;
import {downloadExport} from "@/utils/download";
import router from "@/router";
import {nextTick,onMounted, reactive, ref, shallowRef, onBeforeUnmount,onUpdated,watch} from "vue";
import request from "@/utils/request";
import {ElMessage} from "element-plus";
import config from "../../config";
import {useUserStore} from "@/stores/user";
import AMapLoader from '@amap/amap-jsapi-loader'

const name = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const userStore = useUserStore()
const token = userStore.getBearerToken
const auths =  userStore.getAuths
const user = userStore.getUser

const state = reactive({
  tableData: [],
  form: {}
})

const valueHtml = ref('')  // 富文本内容

state.userOptions = []
request.get('/user').then(res => state.userOptions = res.data)


const multipleSelection = ref([])

// 批量删除
const handleSelectionChange = (val) => {
  multipleSelection.value = val
}

const confirmDelBatch = () => {
  if (!multipleSelection.value || !multipleSelection.value.length) {
    ElMessage.warning("请选择数据")
    return
  }
  const idArr = multipleSelection.value.map(v => v.id)
  request.post('/records/del/batch', idArr).then(res => {
    if (res.code === '200') {
      ElMessage.success('操作成功')
      load()  // 刷新表格数据
    } else {
      ElMessage.error(res.msg)
    }
  })
}

const load = () => {
  request.get('/records/page', {
    params: {
      name: name.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    }
  }).then(res => {
    state.tableData = res.data.records
    total.value = res.data.total
  })
}
load()  // 调用 load方法拿到后台数据

const reset = () => {
  name.value = ''
  load()
}

const dialogFormVisible = ref(false)

const rules = reactive({
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
  ]
})
const ruleFormRef = ref()

// 新增
const handleAdd = () => {
  dialogFormVisible.value = true
  nextTick(() => {
    ruleFormRef.value.resetFields()
    state.form = {}
    valueHtml.value = ''  // 富文本
  })
}

// 保存
const save = () => {
  ruleFormRef.value.validate(valid => {   // valid就是校验的结果
    if (valid) {
      state.form.content = valueHtml.value  // 富文本保存内容
      request.request({
        url: '/records',
        method: state.form.id ? 'put' : 'post',
        data: state.form
      }).then(res => {
        if (res.code === '200') {
          ElMessage.success('保存成功')
          dialogFormVisible.value = false
          load()  // 刷新表格数据
        } else {
          ElMessage.error(res.msg)
        }
      })
    }
  })
}

// 编辑
const handleEdit = (raw) => {
  dialogFormVisible.value = true
  nextTick(() => {
    ruleFormRef.value.resetFields()
    state.form = JSON.parse(JSON.stringify(raw))
    valueHtml.value = raw.content  // 富文本
  })
}

// 删除
const del = (id) => {
  request.delete('/records/' + id).then(res => {
    if (res.code === '200') {
      ElMessage.success('操作成功')
      load()  // 刷新表格数据
    } else {
      ElMessage.error(res.msg)
    }
  })
}

// 导出接口
const exportData = () => {
  downloadExport("/records/export")
}


const handleImportSuccess = () => {
  // 刷新表格
  load()
  ElMessage.success("导入成功")
}

const handleFileUploadSuccess = (res) => {
  state.form.file = res.data
  ElMessage.success('上传成功')
}
const handleImgUploadSuccess = (res) => {
  state.form.img = res.data
  ElMessage.success('上传成功')
}

//人脸识别门禁
const dialogFaceFormVisible = ref(false)
const faceRecord = () =>{
  dialogFaceFormVisible.value = true;
  initMap()
}

state.faceOption ={};
state.cameraState = false
state.faceImgState = false
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
// 监听弹出层 设置摄像头是否开启
watch(() => dialogFaceFormVisible.value, (newValue, oldValue) => {
  if (newValue){
    setTimeout(()=>{
      // 开启摄像头
      state.faceOption = getCamera({
        videoWidth: 200,
        videoHeight: 200,
        thisCancas: null,
        thisContext: null,
        thisVideo: null,
        canvasId:'canvasCamera',
        videoId:'videoCamera'
      });
    },1000)
  } else {
    if(state.faceOption.thisVideo != null){
      // 不等于null 关闭摄像头
      state.faceOption.thisVideo?.srcObject?.getTracks().forEach(track => track.stop());
    }
  }
})

//开始人脸识别
const faceVerify = async () => {
  if (state.faceImgState) return
  if (!state.form.address) { ElMessage.warning('请等待定位成功'); return }
  state.faceImgState = true
  try {
    const imageBase = draw(state.faceOption)
    const res = await request.post('/faceVerify', {imageBase}, {params: {address: state.form.address}})
    if (res.code === '200') {
      dialogFaceFormVisible.value = false
      ElMessage.success('打卡成功')
      load()
    } else ElMessage.error(res.msg)
  } catch { ElMessage.error('识别失败，请检查摄像头和网络后重试') }
  finally {state.faceImgState = false}
}

//地图组件
window._AMapSecurityConfig = {
  securityJsCode:config.securityJsCode,
}
const map = shallowRef(null);//定义一个map对象

//初始化地图
let placeSearch = {}
let geocoder; // 用于逆地理编码的变量
const initMap=()=>{
  AMapLoader.load({
    key:config.key,  //设置密钥
    version:"2.0",//版本
    plugins:['AMap.Geolocation','AMap.PlaceSearch','AMap.Geocoder'],
  }).then((AMap)=>{

    AMap.plugin(['AMap.Geolocation'], () => {

      const geolocation = new AMap.Geolocation({
        enableHighAccuracy: true, // 是否使用高精度定位，默认:true
        timeout: 10000,          // 超过10秒后停止定位，默认：无穷大
        maximumAge: 0,           // 定位结果缓存0毫秒，默认：0
        convert: true,           // 自动偏移定位结果，默认：true
        showMarker: true,        // 定位成功显示定位点
        showCircle: true,        // 定位成功显示精度范围圆
        panToLocation: true,     // 定位成功后自动移动地图到定位点
        zoomToAccuracy: true     // 定位成功后调整地图视野范围使定位位置及精度范围视野内可见
      });
      // 调用 getCurrentPosition 方法进行定位
      geolocation.getCurrentPosition((status, result) => {
        if (status === 'complete') {
          // 定位成功，显示坐标和地址信息
          const lnglat = result.position; // 经纬度信息
          const addressComponent = result.addressComponent; // 地址信息

          console.log('经度:', lnglat.lng);
          console.log('纬度:', lnglat.lat);


          map.value = new AMap.Map("container",{
            viewMode:"3D",//地图模式
            pitch:20,//角度
            zoom:11,//缩放，缩放级别在3-15
            zooms:[2,22],
            mapStyle: 'amap://styles/blue', //设置地图的显示样式
            center:[lnglat.lng,lnglat.lat],//初始化地图中心点位置
          });

          AMap.plugin(['AMap.Geocoder'], () => {
            geocoder = new AMap.Geocoder({
              radius: 1000, // 范围
            });

            // 根据经纬度进行逆地理编码
            geocoder.getAddress([lnglat.lng, lnglat.lat], (status, result) => {
              if (status === 'complete' && result.regeocode) {
                // 获取城市信息
                //const city = result.regeocode.addressComponent.city;
                const fullAddress = result.regeocode.addressComponent.city +result.regeocode.addressComponent.district

                state.form.address = fullAddress+"(经度"+lnglat.lng+",纬度:"+lnglat.lat+")"
                state.form.longitude = lnglat.lng
                state.form.latitude = lnglat.lat

                console.log('所在地点:', state.form.address);
                //添加地点标注
                new AMap.Marker({
                  position: [lnglat.lng, lnglat.lat],
                  map: map.value,
                  title: fullAddress,
                });
              } else {
                console.log('逆地理编码失败', result);
              }
            });
          });

        } else {
          // 定位失败
          console.log('定位失败', result);
        }
      });
    });
  }).catch(e=>{
    console.log(e);
  })
}

</script>

<template>
  <div>


    <div style="margin: 10px 0;text-align:right;">
      <el-button type="primary" @click="faceRecord" v-if="auths.includes('records.add')">
        <el-icon style="vertical-align: middle">
          <Plus />
        </el-icon>  <span style="vertical-align: middle"> 上下班扫脸打卡 </span>
      </el-button>
      <el-upload
          disabled
          v-if="auths.includes('records.import')"
          class="ml5"
          :show-file-list="false"
          style="display: inline-block; position: relative; top: 3px"
          :action='`http://${config.serverUrl}/records/import`'
          :on-success="handleImportSuccess"
          :headers="{ Authorization: token}"
      >
        <el-button type="primary" plain>
          <el-icon style="vertical-align: middle">
            <Bottom />
          </el-icon>  <span style="vertical-align: middle"> 批量导入 </span>
        </el-button>
      </el-upload>
      <el-button type="primary" plain @click="exportData" class="ml5" v-if="auths.includes('records.export')">
        <el-icon style="vertical-align: middle">
          <Top />
        </el-icon>
        <span style="vertical-align: middle"> 批量导出 </span>
      </el-button>
      <el-popconfirm title="您确定删除吗？" @confirm="confirmDelBatch" v-if="auths.includes('records.deleteBatch')">
        <template #reference>
          <el-button type="danger" style="margin-left: 5px">
                <span style="vertical-align: middle"> 批量删除 </span>
          </el-button>
        </template>
      </el-popconfirm>
    </div>

    <div style="margin: 10px 0">
      <el-table :data="state.tableData" stripe border  @selection-change="handleSelectionChange" :header-cell-class-name="'tableHeaderCss'">
        <el-table-column type="selection" width="55" />
        <el-table-column label="序号">
          <template #default="scope">
            {{scope.$index+1}}
          </template>
        </el-table-column>

      <el-table-column label="考勤员工"><template #default="scope"><span v-if="scope.row.userId">{{ state.userOptions.find(v => v.id === scope.row.userId) ? state.userOptions.find(v => v.id === scope.row.userId).name : '' }}</span></template></el-table-column>
        <el-table-column prop="createTime" label="打卡时间"></el-table-column>
        <el-table-column prop="address" label="打卡地点"></el-table-column>
        <el-table-column prop="stateRadio" label="状态"></el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="scope">
            <el-button type="primary" @click="handleEdit(scope.row)" v-if="auths.includes('records.edit')">修改</el-button>
            <el-popconfirm title="您确定删除吗？" @confirm="del(scope.row.id)" v-if="auths.includes('records.delete')">
              <template #reference>
                <el-button type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page">
      <el-pagination
          prev-text="上一页"
          next-text="下一页"
          @current-change="load"
          @size-change="load"
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          background
          :page-sizes="[4, 8, 12, 16]"
          layout="prev, pager, next, total"
          :total="total"
      />
    </div>

    <el-dialog v-model="dialogFormVisible" title="考勤记录信息" width="40%">
      <el-form ref="ruleFormRef" :rules="rules" :model="state.form" label-width="80px" style="padding: 0 20px" status-icon>
        <el-form-item label-width="100" prop="address" label="打卡地点">
          <el-input v-model="state.form.address" autocomplete="off"></el-input>
        </el-form-item>
        <el-form-item label-width="100" prop="stateRadio" label="状态">
          <el-radio-group v-model="state.form.stateRadio">
           <el-radio label="迟到">迟到</el-radio>
           <el-radio label="早退">早退</el-radio>
          </el-radio-group>        </el-form-item>

      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogFormVisible = false">取消</el-button>
        <el-button type="primary" @click="save">
          保存
        </el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogFaceFormVisible" width="40%" title="人脸识别打卡">
      <!--捕获人脸区域-->
      <div class="videoCamera-canvasCamera">
        <video
            id="videoCamera"
            :width="videoWidth"
            :height="videoHeight"
            autoplay
        ></video>
        <canvas
            style="display: none"
            id="canvasCamera"
            :width="videoWidth"
            :height="videoHeight"
        ></canvas>

        <!--人脸特效区域-->
        <div v-if="state.faceImgState" class="face-special-effects-2"></div>
        <div v-else class="face-special-effects"></div>



      </div>

      <div style="width: 60%;margin: 0 auto;">
        <div style="text-align: center;">您的当前打卡的实时位置</div>
        <div id="container" style="height: 40vh; width: 100%;margin-top: 20px;"></div>
      </div>

      <!--按钮区域-->
      <div class="face-btn">
        <el-button type="success" @click="faceVerify()">{{state.faceImgState?'正在识别中...':'确认识别打卡'}}</el-button>
      </div>
    </el-dialog>

  </div>
</template>
<style>
.page {
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 13px;
  color: #656565;
  margin-top: 20px;
  padding-bottom: 20px;
}

.page > div.homePage, .page > div.lastPage {
  height: 34px;
  line-height: 34px;
  width: 60px;
  text-align: center;
  border: 1px solid #EAEAEA;
  box-sizing: border-box;
  cursor: pointer;
}

.page > div.homePage {
  border-radius: 2px 0 0 2px;
}

.page > div.lastPage {
  border-radius: 0 2px 2px 0;
}

.el-pagination {
  text-align: center;
  padding: 0;
}

.el-pagination > button {
  padding: 0 !important;
  height: 34px !important;
}

.el-pagination > button.btn-prev {
  border-right: none !important;
}

.el-pagination span {
  color: #656565;
  width: 50px;
  border: 1px solid #EAEAEA;
  height: 34px !important;
  line-height: 34px !important;
  box-sizing: border-box;
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
