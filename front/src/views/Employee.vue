<script setup>
import {downloadExport} from "@/utils/download";
import FaceEnrollmentDialog from "@/components/FaceEnrollmentDialog.vue";
import {nextTick, reactive, ref} from "vue";
import request from "@/utils/request";
import {ElMessage} from "element-plus";
import config from "../../config";
import {useUserStore} from "@/stores/user";


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

state.deptOptions = []
request.get('/dept').then(res => state.deptOptions = res.data)
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
  request.post('/employee/del/batch', idArr).then(res => {
    if (res.code === '200') {
      ElMessage.success('操作成功')
      load()  // 刷新表格数据
    } else {
      ElMessage.error(res.msg)
    }
  })
}

const load = () => {
  request.get('/employee/page', {
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
  username: [
    { required: true, message: '请输入登录账户', trigger: 'blur' },
  ],
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号码', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '请输入正确的手机号码格式', trigger: ['blur', 'change'] }
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
        url: '/employee',
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
  request.delete('/employee/' + id).then(res => {
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
  downloadExport("/employee/export")
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


const dialogFaceFormVisible = ref(false)
const faceTarget = ref({})
const updateFace = (row) => {
  faceTarget.value = row
  dialogFaceFormVisible.value = true
}
</script>

<template>
  <div>
        <div>
      <el-input v-model="name" placeholder="请输入关键词" class="w300" />
      <el-button type="info" plain class="ml5" @click="load">
        <el-icon style="vertical-align: middle">
          <Search />
        </el-icon>  <span style="vertical-align: middle"> 搜索 </span>
      </el-button>
      <el-button type="warning" plain class="ml5" @click="reset">
        <el-icon style="vertical-align: middle">
          <RefreshLeft />
        </el-icon>  <span style="vertical-align: middle"> 重置 </span>
      </el-button>

    </div>

    <div style="margin: 10px 0;text-align:right;">
      <el-button type="primary" @click="handleAdd" v-if="auths.includes('employee.add')">
        <el-icon style="vertical-align: middle">
          <Plus />
        </el-icon>  <span style="vertical-align: middle"> 添加 </span>
      </el-button>
      <el-upload
          disabled
          v-if="auths.includes('employee.import')"
          class="ml5"
          :show-file-list="false"
          style="display: inline-block; position: relative; top: 3px"
          :action='`http://${config.serverUrl}/employee/import`'
          :on-success="handleImportSuccess"
          :headers="{ Authorization: token}"
      >
        <el-button type="primary" plain>
          <el-icon style="vertical-align: middle">
            <Bottom />
          </el-icon>  <span style="vertical-align: middle"> 批量导入 </span>
        </el-button>
      </el-upload>
      <el-button type="primary" plain @click="exportData" class="ml5" v-if="auths.includes('employee.export')">
        <el-icon style="vertical-align: middle">
          <Top />
        </el-icon>
        <span style="vertical-align: middle"> 批量导出 </span>
      </el-button>
      <el-popconfirm title="您确定删除吗？" @confirm="confirmDelBatch" v-if="auths.includes('employee.deleteBatch')">
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
      <el-table-column label="所属部门"><template #default="scope"><span v-if="scope.row.deptId">{{ state.deptOptions.find(v => v.id === scope.row.deptId) ? state.deptOptions.find(v => v.id === scope.row.deptId).name : '' }}</span></template></el-table-column>
      <el-table-column prop="name" label="员工姓名"></el-table-column>
      <el-table-column prop="phone" label="手机号码"></el-table-column>
      <el-table-column prop="title" label="职位"></el-table-column>
      <el-table-column prop="username" label="登录账户"></el-table-column>

        <el-table-column label="操作" width="300">
          <template #default="scope">
            <el-button type="primary" @click="handleEdit(scope.row)" v-if="auths.includes('employee.edit')">修改</el-button>
            <el-button type="success" @click="updateFace(scope.row)" v-if="user.role === 'ADMIN' && auths.includes('employee.edit')">人脸管理</el-button>
            <el-popconfirm title="您确定删除吗？" @confirm="del(scope.row.id)" v-if="auths.includes('employee.delete')">
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

    <el-dialog v-model="dialogFormVisible" title="员工信息" width="40%">
      <el-form ref="ruleFormRef" :rules="rules" :model="state.form" label-width="80px" style="padding: 0 20px" status-icon>
        <el-form-item label-width="100" prop="deptId" label="所属部门">
          <el-select clearable v-model="state.form.deptId" placeholder="请选择"  style="width: 100%">
            <el-option v-for="item in state.deptOptions" :key="item.id" :label="item.name" :value="item.id"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label-width="100" prop="username" label="登录账户">
          <el-input :disabled="!!state.form.id" v-model="state.form.username" autocomplete="off"></el-input>
        </el-form-item>
        <el-form-item v-if="!state.form.id" label-width="100" label="初始密码">
          <el-input v-model="state.form.initialPassword" show-password placeholder="8至64位" />
        </el-form-item>
        <el-form-item label-width="100" prop="name" label="员工姓名">
          <el-input v-model="state.form.name" autocomplete="off"></el-input>
        </el-form-item>
        <el-form-item label-width="100" prop="phone" label="手机号码">
          <el-input v-model="state.form.phone" autocomplete="off"></el-input>
        </el-form-item>
        <el-form-item label-width="100" prop="title" label="职位">
          <el-input v-model="state.form.title" autocomplete="off"></el-input>
        </el-form-item>
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

    <FaceEnrollmentDialog v-model="dialogFaceFormVisible" :user-id="faceTarget.userId" :name="faceTarget.name" />
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
</style>
