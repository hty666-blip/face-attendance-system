<script setup>
import SafeHtml from "@/components/SafeHtml.vue";
import {reactive} from "vue";
import request from "@/utils/request";
import dayjs from "dayjs";

const state = reactive({
  notice: []
})

const load = () => {
  request.get("/notice").then(res => {
    state.notice = res.data
  })
}

const dateFliter = (val, format = "YYYY-MM-DD hh:mm:ss") => {
  if (!isNaN(val)) {
    val = parseInt(val);
  }
  return dayjs(val).format(format);
};
load()
</script>

<template>
  <div>
    <el-row :gutter="10">

      <el-col :span="24">

        <div style="padding: 30px;">
            <div>
              <span style="font-size: 20px;font-weight:bold;color: #666;">公告列表</span>
            </div>
            <el-collapse accordion>
              <el-collapse-item v-for="(item,index) in state.notice" :key="item.id" :name="'' + index">
                <template #title>
                  <span style="font-size: 16px;">{{ item.name }}</span>
                  <span style="margin-left: 10px">时间：{{ item.createTime }}</span>
                </template>
                <SafeHtml :html="item.content" />
              </el-collapse-item>
            </el-collapse>
        </div>

      </el-col>
    </el-row>

    <el-divider />


  </div>
</template>
