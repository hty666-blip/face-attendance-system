<script setup>
import ProtectedImage from "@/components/ProtectedImage.vue";
import {computed, ref, watch} from 'vue'
  import { RouterView } from 'vue-router'
  import router from "@/router";
  import {useUserStore} from "@/stores/user";
  import request from "@/utils/request";
  import {ElMessage} from "element-plus";
  const menuOpen = ref(false)
  watch(() => router.currentRoute.value.path, () => {menuOpen.value = false})
  const userStore = useUserStore()
  let user = userStore.getUser
  const activePath = computed(() => router.currentRoute.value.path.replace('/', ''))
  const attendanceVisible = computed(() => userStore.getAuths.includes('records.list') || userStore.getAuths.includes('leaveapply.list'))

  const logout = () => {
    request.get('/logout/' + user.uid).then(res => {
      if (res.code === '200') {
        userStore.logout()
      } else {
        ElMessage.error(res.msg)
      }
    })
  }
  const menus = userStore.getMenus

  const getAvatarHandler = (avatar) => {
    user.avatar = avatar
  }
</script>

<template>
  <div>

    <div class="manager-shell">
      <button v-if="menuOpen" class="manager-menu-backdrop" type="button" aria-label="关闭导航菜单" @click="menuOpen = false"></button>
      <div id="manager-navigation" class="manager-main-left" :class="{'mobile-open': menuOpen}" @keydown.esc="menuOpen = false">
        <div style="line-height:50px;text-align: center;color: white;font-weight: bold;" class="manager-header">
          当前用户：{{ user.name }}
        </div>
        <el-menu
                :default-active="activePath"
                class="el-menu-demo"
                style="border: none"
                router @select="menuOpen = false"
        >
          <el-menu-item v-if="attendanceVisible" index="attendance"><el-icon><Calendar /></el-icon><span>考勤工作台</span></el-menu-item>
          <div v-for="item in menus" :key="item.id">
            <div v-if="item.type === 2">
              <el-menu-item :index="item.path" v-if="!item.hide">
                <el-icon v-if="item.icon">
                  <component :is="item.icon"></component>
                </el-icon>
                <span>{{ item.name }}</span>
              </el-menu-item>
            </div>
            <div v-else>
              <el-sub-menu :index="item.id + ''" v-if="!item.hide">
                <template #title>
                  <el-icon v-if="item.icon">
                    <component :is="item.icon"></component>
                  </el-icon>
                  <span>{{ item.name }}</span>
                </template>
                <div  v-for="subItem in item.children" :key="subItem.id">
                  <el-menu-item :index="subItem.path" v-if="!subItem.hide">
                    <template #title>
                      <el-icon v-if="subItem.icon">
                        <component :is="subItem.icon"></component>
                      </el-icon>
                      <span>{{ subItem.name }}</span>
                    </template>
                  </el-menu-item>
                </div>
              </el-sub-menu>
            </div>
          </div>
        </el-menu>
      </div>

      <div class="manager-main-content">
        <div>
          <div class="manager-toolbar">
            <el-button class="manager-menu-toggle" :aria-expanded="menuOpen" aria-controls="manager-navigation" @click="menuOpen = !menuOpen">菜单</el-button>
            <div class="manager-app-title">
              办公室上下班打卡系统
            </div>

            <div class="manager-account">
              <div>
              </div>
              <div style="text-align: right; padding-right: 20px;line-height: 60px;">
                <el-dropdown>
                  <ProtectedImage :src="user.avatar" style="margin-top:10px;width:40px;height:40px;border-radius:50%" />
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item><div @click="router.push('/person')">个人信息</div></el-dropdown-item>
                      <el-dropdown-item><div @click="router.push('/password')">修改密码</div></el-dropdown-item>
                      <el-dropdown-item><div @click="logout">退出登录</div></el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
            </div>
          </div>
        </div>
        <div style="padding: 10px;">
          <router-view v-slot="{ Component }">
            <component @getAvatar="getAvatarHandler" ref="childRef" :is="Component" />
          </router-view>
        </div>
      </div>
    </div>

  </div>
</template>
<style scoped>
  @import "@/assets/css/layout.css";
  .manager-shell {display:flex;min-height:100vh}
  .manager-main-content {flex:1;min-width:0}
  .manager-main-left {flex-shrink:0}
  .manager-toolbar {display:flex;align-items:center;gap:12px;min-height:60px}
  .manager-app-title {flex:1;min-width:0;color:#555e6d;font-weight:600;font-size:20px;text-align:center;line-height:1.5}
  .manager-account {width:64px;flex-shrink:0}
  .manager-menu-toggle,.manager-menu-backdrop {display:none}
  @media (max-width:760px) {
    .manager-toolbar {padding:0 10px;gap:8px}
    .manager-app-title {font-size:18px;text-align:left}
    .manager-main-left {display:none}
    .manager-main-left.mobile-open {display:block;position:fixed;inset:0 auto 0 0;width:220px;max-height:100vh;overflow-y:auto;z-index:1999;background:#fff}
    .manager-main-left.mobile-open :deep(.el-menu) {width:220px}
    .manager-menu-toggle {display:inline-flex;min-height:44px;margin:0}
    .manager-menu-backdrop {display:block;position:fixed;inset:0;width:100%;height:100%;padding:0;border:0;background:rgba(0,0,0,.35);z-index:1998}
    .manager-account {width:44px}
  }
</style>
