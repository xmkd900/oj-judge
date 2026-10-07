<template>
  <div id="app">
    <!-- 只有登录/注册走独立的居中布局；个人中心要带导航条，走 BasicLayout -->
    <template v-if="standalonePaths.includes(route.path)">
      <router-view />
    </template>
    <template v-else>
      <BasicLayout />
    </template>
  </div>
</template>

<style>
#app {
}
</style>
<script setup lang="ts">
import BasicLayout from "@/layouts/BasicLayout.vue";
import { onMounted } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();

/** 使用无导航独立布局的页面 */
const standalonePaths = ["/user/login", "/user/register"];

/**
 * 全局初始化函数，有全局单次调用的代码，都可以写到这里
 */
const doInit = () => {
  console.log("hello 欢迎来到我的项目");
};

onMounted(() => {
  doInit();
});
</script>
