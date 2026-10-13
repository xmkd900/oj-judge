<template>
  <div id="userManageView">
    <a-form :model="searchParams" layout="inline" style="margin-bottom: 16px">
      <a-form-item field="userName" label="昵称">
        <a-input
          v-model="searchParams.userName"
          placeholder="模糊搜索"
          allow-clear
        />
      </a-form-item>
      <a-form-item field="userRole" label="角色">
        <a-select
          v-model="searchParams.userRole"
          :options="roleOptions"
          placeholder="全部"
          allow-clear
          :style="{ width: '160px' }"
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="doSearch">搜索</a-button>
      </a-form-item>
    </a-form>

    <a-table
      :columns="columns"
      :data="dataList"
      :pagination="{
        showTotal: true,
        pageSize: searchParams.pageSize,
        current: searchParams.current,
        total,
      }"
      @page-change="onPageChange"
    >
      <template #userRole="{ record }">
        <a-tag :color="roleColor(record.userRole)">
          {{ roleText(record.userRole) }}
        </a-tag>
      </template>
      <template #createTime="{ record }">
        {{ formatDateTime(record.createTime) }}
      </template>
      <template #optional="{ record }">
        <a-space>
          <a-button size="mini" @click="openEdit(record)">编辑</a-button>
          <a-button
            v-if="record.userRole === 'ban'"
            size="mini"
            status="success"
            @click="doUnban(record)"
          >
            解封
          </a-button>
          <a-popconfirm
            v-else
            :content="`确定封禁 ${record.userName || record.userAccount} ？封禁后将无法登录`"
            @ok="doBan(record)"
          >
            <a-button size="mini" status="warning">封禁</a-button>
          </a-popconfirm>
        </a-space>
      </template>
    </a-table>

    <!-- 编辑弹窗 -->
    <a-modal
      v-model:visible="editVisible"
      :title="`编辑用户 #${editing.id}`"
      @ok="doUpdate"
      @cancel="editVisible = false"
    >
      <a-form :model="editing" auto-label-width>
        <a-form-item label="账号">
          <a-input v-model="editing.userAccount" disabled />
        </a-form-item>
        <a-form-item label="昵称">
          <a-input v-model="editing.userName" />
        </a-form-item>
        <a-form-item label="简介">
          <a-textarea v-model="editing.userProfile" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model="editing.userRole" :options="roleOptions" />
        </a-form-item>
        <a-form-item v-if="isSelf" label="提示">
          <a-alert type="warning">
            这是你自己，修改角色会被后端拒绝（防止把自己降权后没人能进后台）
          </a-alert>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useStore } from "vuex";
import * as adminApi from "@/api/admin";
import { UserVO } from "@/api/admin";
import { formatDateTime } from "@/utils/date";
import message from "@arco-design/web-vue/es/message";

const store = useStore();
const currentUserId = computed(() => store.state.user.loginUser?.id);

const roleOptions = [
  { label: "普通用户", value: "user" },
  { label: "管理员", value: "admin" },
  { label: "已封禁", value: "ban" },
];

const roleText = (role?: string) => {
  switch (role) {
    case "admin":
      return "管理员";
    case "ban":
      return "已封禁";
    default:
      return "普通用户";
  }
};

const roleColor = (role?: string) => {
  switch (role) {
    case "admin":
      return "red";
    case "ban":
      return "gray";
    default:
      return "green";
  }
};

const dataList = ref<UserVO[]>([]);
const total = ref(0);
const searchParams = reactive<adminApi.UserQueryRequest>({
  userName: "",
  userRole: undefined,
  pageSize: 10,
  current: 1,
  sortField: "createTime",
  sortOrder: "descend",
});

const loadData = async () => {
  try {
    const data = await adminApi.listUserByPage(searchParams);
    dataList.value = data.records;
    total.value = data.total;
  } catch (error: any) {
    message.error("加载失败：" + (error?.message ?? "请稍后重试"));
  }
};

onMounted(loadData);

const doSearch = () => {
  searchParams.current = 1;
  loadData();
};

const onPageChange = (page: number) => {
  searchParams.current = page;
  loadData();
};

// ---------------------------------------------------------------- 编辑

const editVisible = ref(false);
const editing = reactive<adminApi.UserUpdateRequest & { userAccount?: string }>({
  id: 0,
  userName: "",
  userProfile: "",
  userRole: "user",
  userAccount: "",
});

const isSelf = computed(() => editing.id === currentUserId.value);

const openEdit = (record: UserVO) => {
  editing.id = record.id ?? 0;
  editing.userName = record.userName ?? "";
  editing.userProfile = record.userProfile ?? "";
  editing.userRole = record.userRole ?? "user";
  editing.userAccount = record.userAccount ?? "";
  editVisible.value = true;
};

const doUpdate = async () => {
  try {
    await adminApi.updateUser({
      id: editing.id,
      userName: editing.userName,
      userProfile: editing.userProfile,
      userRole: editing.userRole,
    });
    message.success("保存成功");
    editVisible.value = false;
    loadData();
  } catch (error: any) {
    message.error("保存失败：" + (error?.message ?? "请稍后重试"));
  }
};

// ---------------------------------------------------------------- 封禁

const doBan = async (record: UserVO) => {
  if (!record.id) return;
  try {
    await adminApi.banUser(record.id);
    message.success("已封禁");
    loadData();
  } catch (error: any) {
    message.error("封禁失败：" + (error?.message ?? "请稍后重试"));
  }
};

const doUnban = async (record: UserVO) => {
  if (!record.id) return;
  try {
    await adminApi.unbanUser(record.id);
    message.success("已解封");
    loadData();
  } catch (error: any) {
    message.error("解封失败：" + (error?.message ?? "请稍后重试"));
  }
};

const columns = [
  { title: "id", dataIndex: "id" },
  { title: "账号", dataIndex: "userAccount" },
  { title: "昵称", dataIndex: "userName" },
  { title: "角色", slotName: "userRole" },
  { title: "注册时间", slotName: "createTime" },
  { title: "操作", slotName: "optional" },
];
</script>

<style scoped>
#userManageView {
  max-width: 1280px;
  margin: 0 auto;
}
</style>
