<template>
  <div id="userRegisterView">
    <h2 style="margin-bottom: 16px">用户注册</h2>
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
        :rules="[
          { required: true, message: '请输入账号' },
          { minLength: 4, message: '账号至少 4 位' },
        ]"
      >
        <a-input
          v-model="form.userAccount"
          placeholder="请输入账号（至少 4 位）"
        />
      </a-form-item>
      <a-form-item
        field="userPassword"
        label="密码"
        :rules="[
          { required: true, message: '请输入密码' },
          { minLength: 6, message: '密码至少 6 位' },
        ]"
      >
        <a-input-password
          v-model="form.userPassword"
          placeholder="请输入密码（至少 6 位）"
        />
      </a-form-item>
      <a-form-item
        field="checkPassword"
        label="确认密码"
        :rules="[
          { required: true, message: '请再次输入密码' },
          { validator: sameAsPassword },
        ]"
      >
        <a-input-password
          v-model="form.checkPassword"
          placeholder="请再次输入密码"
          @press-enter="handleSubmit"
        />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit" :loading="submitting">
            注册
          </a-button>
          <a-button type="text" @click="toLogin">已有账号？去登录</a-button>
        </a-space>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import message from "@arco-design/web-vue/es/message";
import * as authApi from "@/api/auth";
import { UserRegisterRequest } from "@/api/auth";

const form = reactive<UserRegisterRequest>({
  userAccount: "",
  userPassword: "",
  checkPassword: "",
});

const submitting = ref(false);
const router = useRouter();
const route = useRoute();

/** Arco 自定义校验：确认密码必须与密码一致 */
const sameAsPassword = (value: string, callback: (error?: string) => void) => {
  if (value !== form.userPassword) {
    callback("两次输入的密码不一致");
    return;
  }
  callback();
};

const handleSubmit = async () => {
  if (submitting.value) return;
  submitting.value = true;
  try {
    await authApi.register(form);
    message.success("注册成功，请登录");
    await router.push({
      path: "/user/login",
      query: route.query.redirect ? { redirect: route.query.redirect } : {},
    });
  } catch (error: any) {
    message.error("注册失败：" + (error?.message ?? "请检查填写内容"));
  } finally {
    submitting.value = false;
  }
};

const toLogin = () => {
  router.push({
    path: "/user/login",
    query: route.query.redirect ? { redirect: route.query.redirect } : {},
  });
};
</script>
