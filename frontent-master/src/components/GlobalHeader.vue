<template>
  <a-row id="globalHeader" align="center" :wrap="false">
    <a-col flex="auto">
      <a-menu
        mode="horizontal"
        :selected-keys="selectedKeys"
        @menu-item-click="doMenuClick"
      >
        <a-menu-item
          key="0"
          :style="{ padding: 0, marginRight: '38px' }"
          disabled
        >
          <div class="title-bar">
            <img class="logo" src="../assets/oj-logo.png" />
            <div class="title">OJ</div>
          </div>
        </a-menu-item>
        <a-menu-item v-for="item in visibleRoutes" :key="item.path">
          {{ item.name }}
        </a-menu-item>
      </a-menu>
    </a-col>
    <a-col flex="160px">
      <div class="user-area">
        <!-- 已登录：头像 + 下拉菜单（个人中心 / 退出登录） -->
        <a-dropdown v-if="isLogin" @select="onUserMenuSelect">
          <a-button type="text" class="user-trigger">
            <a-space :size="6">
              <a-avatar
                :size="24"
                :image-url="loginUser.userAvatar || undefined"
              >
                {{ (loginUser.userName || "?").slice(0, 1) }}
              </a-avatar>
              <span class="user-name">{{ loginUser.userName || "未命名" }}</span>
            </a-space>
          </a-button>
          <template #content>
            <a-doption value="profile">个人中心</a-doption>
            <a-doption value="logout">退出登录</a-doption>
          </template>
        </a-dropdown>

        <!-- 未登录：登录 / 注册 -->
        <a-space v-else :size="4">
          <a-button type="text" @click="go('/user/login')">登录</a-button>
          <a-button type="primary" size="small" @click="go('/user/register')">
            注册
          </a-button>
        </a-space>
      </div>
    </a-col>
  </a-row>
</template>

<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useStore } from "vuex";
import message from "@arco-design/web-vue/es/message";
import { routes } from "../router/routes";
import checkAccess from "@/access/checkAccess";
import ACCESS_ENUM from "@/access/accessEnum";

const router = useRouter();
const route = useRoute();
const store = useStore();

const loginUser = computed(() => store.state.user.loginUser ?? {});

const isLogin = computed(() => {
  const role = loginUser.value.userRole;
  return !!role && role !== ACCESS_ENUM.NOT_LOGIN;
});

// 展示在菜单的路由数组（按权限过滤）
const visibleRoutes = computed(() => {
  return routes.filter((item) => {
    if (item.meta?.hideInMenu) {
      return false;
    }
    return checkAccess(store.state.user.loginUser, item?.meta?.access as string);
  });
});

// 默认主页
const selectedKeys = ref(["/"]);

// 路由跳转后，更新选中的菜单项
router.afterEach((to) => {
  selectedKeys.value = [to.path];
});

const doMenuClick = (key: string) => {
  router.push({ path: key });
};

/** 跳登录/注册时带上当前地址，方便登录完原路返回 */
const go = (path: string) => {
  router.push({
    path,
    query: route.path.startsWith("/user") ? {} : { redirect: route.fullPath },
  });
};

const onUserMenuSelect = async (value: string) => {
  if (value === "profile") {
    router.push("/user/profile");
    return;
  }
  if (value === "logout") {
    await store.dispatch("user/userLogout");
    message.success("已退出登录");
    await router.push({ path: "/user/login", replace: true });
  }
};
</script>

<style scoped>
.title-bar {
  display: flex;
  align-items: center;
}

.title {
  color: #444;
  margin-left: 16px;
}

.logo {
  height: 48px;
}

.user-area {
  display: flex;
  justify-content: flex-end;
  align-items: center;
}

.user-name {
  max-width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
