<template>
  <div id="userLoginView">
    <h2 style="margin-bottom: 16px">用户登录</h2>
    <a-form
      style="max-width: 480px; margin: 0 auto"
      label-align="left"
      auto-label-width
      :model="form"
      @submit-success="handleSubmit"
    >
      <a-form-item
        field="userAccount"
        label="账号"
        :rules="[{ required: true, message: '请输入账号' }]"
      >
        <a-input v-model="form.userAccount" placeholder="请输入账号" />
      </a-form-item>
      <a-form-item
        field="userPassword"
        label="密码"
        tooltip="密码不少于 6 位"
        :rules="[{ required: true, message: '请输入密码' }]"
      >
        <a-input-password
          v-model="form.userPassword"
          placeholder="请输入密码"
          @press-enter="handleSubmit"
        />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit" :loading="submitting">
            登录
          </a-button>
          <a-button type="text" @click="toRegister">还没有账号？去注册</a-button>
        </a-space>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useStore } from "vuex";
import message from "@arco-design/web-vue/es/message";
import * as authApi from "@/api/auth";
import { UserLoginRequest } from "@/api/auth";

/**
 * 表单信息
 */
const form = reactive<UserLoginRequest>({
  userAccount: "",
  userPassword: "",
});

const submitting = ref(false);
const router = useRouter();
const route = useRoute();
const store = useStore();

/**
 * 提交登录。
 * access token 已由 authApi.login 写入内存 + localStorage，
 * refresh token 由后端通过 HttpOnly Cookie 下发，前端不感知。
 */
const handleSubmit = async () => {
  if (submitting.value) return;
  submitting.value = true;
  try {
    const data = await authApi.login(form);
    // 顺手把用户信息放进 store，省得路由守卫再打一次接口
    store.commit("user/updateUser", {
      ...data.user,
      userRole: data.user.userRole ?? "user",
    });
    message.success("登录成功");
    const redirect = (route.query.redirect as string) || "/";
    await router.push({ path: redirect, replace: true });
  } catch (error: any) {
    message.error("登录失败：" + (error?.message ?? "请检查账号和密码"));
  } finally {
    submitting.value = false;
  }
};

const toRegister = () => {
  router.push({
    path: "/user/register",
    query: route.query.redirect ? { redirect: route.query.redirect } : {},
  });
};
</script>
