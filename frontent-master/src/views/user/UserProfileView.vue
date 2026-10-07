<template>
  <div id="userProfileView">
    <a-card title="个人中心" style="max-width: 640px; margin: 0 auto">
      <a-descriptions :column="1" bordered style="margin-bottom: 24px">
        <a-descriptions-item label="用户 ID">
          {{ loginUser.id ?? "-" }}
        </a-descriptions-item>
        <a-descriptions-item label="角色">
          <a-tag :color="roleColor">{{ roleText }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="注册时间">
          {{ formatTime(loginUser.createTime) }}
        </a-descriptions-item>
      </a-descriptions>

      <a-form
        :model="form"
        label-align="left"
        auto-label-width
        @submit-success="handleSubmit"
      >
        <a-form-item field="userName" label="昵称">
          <a-input
            v-model="form.userName"
            placeholder="给自己起个昵称"
            :max-length="32"
            show-word-limit
          />
        </a-form-item>
        <a-form-item field="userAvatar" label="头像">
          <a-space direction="vertical" fill>
            <a-input v-model="form.userAvatar" placeholder="头像图片地址" />
            <a-avatar :size="64" :image-url="form.userAvatar || undefined">
              {{ (form.userName || "?").slice(0, 1) }}
            </a-avatar>
          </a-space>
        </a-form-item>
        <a-form-item field="userProfile" label="个人简介">
          <a-textarea
            v-model="form.userProfile"
            placeholder="介绍一下自己"
            :max-length="512"
            show-word-limit
            :auto-size="{ minRows: 3, maxRows: 6 }"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit" :loading="submitting">
              保存
            </a-button>
            <a-button @click="resetForm">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useStore } from "vuex";
import message from "@arco-design/web-vue/es/message";
import * as authApi from "@/api/auth";
import { UserUpdateMyRequest } from "@/api/auth";
import ACCESS_ENUM from "@/access/accessEnum";

const store = useStore();
const submitting = ref(false);

const loginUser = computed(() => store.state.user.loginUser ?? {});

const form = reactive<UserUpdateMyRequest>({
  userName: "",
  userAvatar: "",
  userProfile: "",
});

const roleText = computed(() => {
  switch (loginUser.value.userRole) {
    case ACCESS_ENUM.ADMIN:
      return "管理员";
    case ACCESS_ENUM.USER:
      return "普通用户";
    default:
      return "未登录";
  }
});

const roleColor = computed(() => {
  switch (loginUser.value.userRole) {
    case ACCESS_ENUM.ADMIN:
      return "red";
    case ACCESS_ENUM.USER:
      return "green";
    default:
      return "gray";
  }
});

const formatTime = (value?: string) => {
  if (!value) return "-";
  return String(value).replace("T", " ");
};

/** 用 store 里的用户信息回填表单 */
const resetForm = () => {
  form.userName = loginUser.value.userName ?? "";
  form.userAvatar = loginUser.value.userAvatar ?? "";
  form.userProfile = loginUser.value.userProfile ?? "";
};

// 首次进入和登录用户信息变化时都同步一次表单
onMounted(resetForm);
watch(loginUser, resetForm, { deep: true });

const handleSubmit = async () => {
  if (submitting.value) return;
  submitting.value = true;
  try {
    await authApi.updateMy({
      userName: form.userName,
      userAvatar: form.userAvatar,
      userProfile: form.userProfile,
    });
    message.success("保存成功");
    // 重新拉一次，保证 store 与后端一致
    await store.dispatch("user/getLoginUser");
  } catch (error: any) {
    message.error("保存失败：" + (error?.message ?? "请稍后重试"));
  } finally {
    submitting.value = false;
  }
};
</script>
