<script setup>
import {downloadExport} from "@/utils/download";
import CameraCapture from "@/components/CameraCapture.vue";
import {CheckInAttempt} from "@/utils/face-checkin.mjs";
import '@/assets/css/face-dialog.css';
import {nextTick, reactive, ref, shallowRef, onBeforeUnmount, watch} from "vue";
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

// 本次打卡的照片仅保留在内存；网络结果未知时复用原请求。
const dialogFaceFormVisible = ref(false)
const checkIn = reactive(new CheckInAttempt(request))
const camera = ref()
const locationError = ref(''), locating = ref(false), address = ref('')
const map = shallowRef(null)
let locationGeneration = 0
const stopLocation = () => {
  locationGeneration++; locating.value = false
  map.value?.destroy(); map.value = null
}
const locate = async () => {
  stopLocation(); address.value = ''; locationError.value = ''; locating.value = true
  const generation = locationGeneration
  const current = () => generation === locationGeneration && dialogFaceFormVisible.value
  if (!config.key || !config.securityJsCode) {
    locationError.value = '地图尚未配置，请联系管理员填写地图配置后重试。'; locating.value = false; return
  }
  try {
    window._AMapSecurityConfig = {securityJsCode: config.securityJsCode}
    const AMap = await AMapLoader.load({key: config.key, version: '2.0', plugins: ['AMap.Geolocation', 'AMap.Geocoder']})
    if (!current()) return
    const geolocation = new AMap.Geolocation({enableHighAccuracy: true, timeout: 10000, maximumAge: 0})
    geolocation.getCurrentPosition((status, result) => {
      if (!current()) return
      if (status !== 'complete' || !result.position) {
        locationError.value = '定位失败，请允许浏览器访问位置后重新定位。'; locating.value = false; return
      }
      const {lng, lat} = result.position
      const geocoder = new AMap.Geocoder({radius: 1000})
      geocoder.getAddress([lng, lat], (geoStatus, response) => {
        if (!current()) return
        locating.value = false
        if (geoStatus !== 'complete' || !response.regeocode?.formattedAddress) {
          locationError.value = '暂时无法获取当前位置的地址，请重新定位。'; return
        }
        address.value = `${response.regeocode.formattedAddress}(经度${lng},纬度${lat})`
        map.value = new AMap.Map('checkin-map', {zoom: 15, center: [lng, lat]})
        new AMap.Marker({position: [lng, lat], map: map.value})
      })
    })
  } catch {
    if (current()) {locationError.value = '地图服务加载失败，请检查网络和地图配置后重试。'; locating.value = false}
  }
}
const faceRecord = () => {dialogFaceFormVisible.value = true}
const prepareCheckIn = () => {camera.value?.reset(); checkIn.prepare()}
const verifyCapture = async (images) => {
  if (await checkIn.capture(images[0], address.value)) completeCheckIn()
}
const retryAttempt = async () => {if (await checkIn.submit()) completeCheckIn()}
const completeCheckIn = () => {
  ElMessage.success('打卡成功'); dialogFaceFormVisible.value = false; load()
}
watch(dialogFaceFormVisible, async open => {
  if (open) {prepareCheckIn(); await nextTick(); if (dialogFaceFormVisible.value) locate()}
  else {stopLocation(); checkIn.clear(); address.value = ''; load()}
})
onBeforeUnmount(() => {stopLocation(); checkIn.clear()})

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

    <el-dialog v-model="dialogFaceFormVisible" width="min(720px, 94vw)" title="人脸识别打卡" top="5vh" class="face-dialog" append-to-body destroy-on-close
               :close-on-click-modal="false" :close-on-press-escape="checkIn.phase !== 'verifying'" :show-close="checkIn.phase !== 'verifying'">
      <div class="checkin-content">
        <p v-if="checkIn.phase === 'preparing'" role="status">正在准备本次打卡…</p>
        <p v-if="checkIn.phase === 'ready'" role="status">本次验证限时两分钟，请定位完成后拍照。</p>
        <CameraCapture ref="camera" :active="dialogFaceFormVisible && ['ready', 'verifying', 'processing', 'uncertain'].includes(checkIn.phase)"
                       :disabled="checkIn.phase !== 'ready' || !address" @captured="verifyCapture" />
        <p v-if="checkIn.phase === 'verifying'" role="status" aria-live="polite">正在验证，请稍候…</p>
        <el-alert v-if="checkIn.error" :title="checkIn.error" type="error" :closable="false" show-icon />
        <el-button v-if="['uncertain', 'processing'].includes(checkIn.phase)" type="primary" @click="retryAttempt">重试本次验证</el-button>
        <el-button v-if="checkIn.phase === 'failed'" @click="prepareCheckIn">重新准备打卡</el-button>
        <div class="checkin-location">
          <p v-if="locating" role="status">正在获取当前位置…</p>
          <p v-if="address" role="status">当前位置：{{ address }}</p>
          <el-alert v-if="locationError" :title="locationError" type="warning" :closable="false" show-icon />
          <el-button :loading="locating" :disabled="checkIn.phase === 'verifying' || !!checkIn.attempt" @click="locate">重新定位</el-button>
          <div id="checkin-map" class="checkin-map" :hidden="!address" aria-label="当前位置地图"></div>
        </div>
      </div>
      <template #footer><el-button :disabled="checkIn.phase === 'verifying'" @click="dialogFaceFormVisible = false">关闭</el-button></template>
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
.checkin-content {display:grid;gap:16px;min-width:0;font-size:16px}
.checkin-content p {margin:0;line-height:1.6;overflow-wrap:anywhere}
.checkin-location {display:grid;gap:12px;padding-top:8px}
.checkin-location .el-button {justify-self:start}
.checkin-map {height:220px;width:100%}
</style>
