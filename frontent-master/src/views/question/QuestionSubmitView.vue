<template>
  <div id="questionSubmitView">
    <a-form :model="searchParams" layout="inline">
      <a-form-item field="questionId" label="题号" style="min-width: 240px">
        <a-input v-model="searchParams.questionId" placeholder="请输入" />
      </a-form-item>
      <a-form-item field="language" label="编程语言" style="min-width: 240px">
        <a-select
          v-model="searchParams.language"
          :style="{ width: '320px' }"
          placeholder="选择编程语言"
        >
          <a-option>java</a-option>
          <a-option>cpp</a-option>
          <a-option>go</a-option>
          <a-option>html</a-option>
        </a-select>
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="doSubmit">搜索</a-button>
      </a-form-item>
    </a-form>
    <!-- 后端对普通用户强制只返回自己的提交，这里如实告知，避免用户以为"别人的记录去哪了" -->
    <a-alert v-if="!isAdmin" type="normal" style="margin-bottom: 12px">
      这里只显示你自己的提交记录
    </a-alert>
    <a-divider size="0" />
    <a-table
      ref="tableRef"
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
      <template #judgeInfo="{ record }">
        <a-space direction="vertical" size="mini">
          <a-tag :color="statusColor(record.judgeInfo?.message)">
            {{ record.judgeInfo?.message ?? "等待中" }}
          </a-tag>
          <span v-if="record.judgeInfo?.time != null" class="judge-meta">
            耗时 {{ formatTime(record.judgeInfo.time) }}
          </span>
          <span v-if="record.judgeInfo?.memory != null" class="judge-meta">
            内存 {{ formatMemory(record.judgeInfo.memory) }}
          </span>
          <!-- 失败时把原始报错展开给用户看。只给"编译错误"四个字等于没说 -->
          <a-popover
            v-if="record.judgeInfo?.errorMessage"
            :content-style="{ maxWidth: '480px' }"
          >
            <a-link size="mini">查看错误详情</a-link>
            <template #content>
              <pre class="error-detail">{{ record.judgeInfo.errorMessage }}</pre>
            </template>
          </a-popover>
        </a-space>
      </template>
      <template #createTime="{ record }">
        {{ formatDate(record.createTime) }}
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watchEffect } from "vue";
import * as questionApi from "@/api/question";
import {
  QuestionSubmitQueryRequest,
  QuestionSubmitVO,
  QuestionVO,
} from "@/api/question";
import { formatDate } from "@/utils/date";
import { formatMemory, formatTime } from "@/utils/format";
import message from "@arco-design/web-vue/es/message";
import { useRouter } from "vue-router";
import { useStore } from "vuex";
import ACCESS_ENUM from "@/access/accessEnum";

const store = useStore();

/** 管理员能看全部提交；普通用户的列表已被后端收窄为只看自己 */
const isAdmin = computed(
  () => store.state.user?.loginUser?.userRole === ACCESS_ENUM.ADMIN
);

const tableRef = ref();

const dataList = ref<QuestionSubmitVO[]>([]);
const total = ref(0);
const searchParams = ref<QuestionSubmitQueryRequest>({
  questionId: undefined,
  language: undefined,
  pageSize: 10,
  current: 1,
});

const loadData = async () => {
  try {
    const data = await questionApi.listQuestionSubmitByPage({
      ...searchParams.value,
      sortField: "createTime",
      sortOrder: "descend",
    });
    dataList.value = data.records;
    total.value = data.total;
    startPollingIfNeeded();
  } catch (error: any) {
    message.error("加载失败：" + (error?.message ?? "请稍后重试"));
  }
};

/**
 * watchEffect 会立即执行一次，无需再写 onMounted，否则首屏重复请求两次。
 */
watchEffect(() => {
  loadData();
});

/**
 * 结论对应的标签颜色。
 *
 * ⚠️ 只认 "Accepted" 才是通过 —— judgeInfo.message 是中英文混杂的，
 * 别按英文枚举去匹配，其余一律视为未通过。
 */
const statusColor = (message?: string) => {
  if (message === "Accepted") return "green";
  if (!message) return "gray";
  return "red";
};

/** 还没出结果：等待中 / 判题中 */
const isPending = (record: QuestionSubmitVO) =>
  record.status === 0 || record.status === 1;

/**
 * 有提交还在排队时自动刷新。
 *
 * 为什么用轮询而不是 WebSocket：判题耗时几秒到几十秒，量级上轮询完全够用，
 * 引入长连接反而要处理重连、鉴权、多实例广播一堆事。等真有实时通知需求再换。
 */
const POLL_INTERVAL_MS = 2000;
let pollTimer: number | undefined;

const startPollingIfNeeded = () => {
  if (pollTimer !== undefined) return;
  const hasPending = dataList.value.some(isPending);
  if (!hasPending) return;
  pollTimer = window.setInterval(async () => {
    await loadData();
    // 全部出结果就停，避免离开页面后还在后台刷
    if (!dataList.value.some(isPending)) {
      stopPolling();
    }
  }, POLL_INTERVAL_MS);
};

const stopPolling = () => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer);
    pollTimer = undefined;
  }
};

/**
 * ⚠️ 这行必须放在 stopPolling 声明<b>之后</b>。
 *
 * const 声明存在暂时性死区（TDZ）：在声明之前访问会抛
 * ReferenceError，导致整个 <script setup> 初始化中断、组件渲染不出来
 * （现象就是页面一片空白）。之前误把它写在文件上方，提交记录页直接白屏。
 */
onUnmounted(stopPolling);

const columns = computed(() => {
  const list = [
    {
      title: "提交号",
      dataIndex: "id",
    },
    {
      title: "编程语言",
      dataIndex: "language",
    },
    {
      title: "判题信息",
      slotName: "judgeInfo",
    },
    {
      title: "判题状态",
      dataIndex: "status",
    },
    {
      title: "题目 id",
      dataIndex: "questionId",
    },
    {
      title: "创建时间",
      slotName: "createTime",
    },
  ];
  // 列表已经收窄为只看自己的，这一列对普通用户恒等于自己，没有信息量
  if (isAdmin.value) {
    list.splice(5, 0, { title: "提交者 id", dataIndex: "userId" });
  }
  return list;
});

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page,
  };
};

const router = useRouter();

/**
 * 跳转到做题页面
 * @param question
 */
const toQuestionPage = (question: QuestionVO) => {
  router.push({
    path: `/view/question/${question.id}`,
  });
};

/**
 * 确认搜索，重新加载数据
 */
const doSubmit = () => {
  // 这里需要重置搜索页号
  searchParams.value = {
    ...searchParams.value,
    current: 1,
  };
};
</script>

<style scoped>
#questionSubmitView {
  max-width: 1280px;
  margin: 0 auto;
}

.judge-meta {
  font-size: 12px;
  color: var(--color-text-3, #86909c);
}

.error-detail {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  padding: 8px 10px;
  background: var(--color-fill-2, #f7f8fa);
  border-radius: 4px;
  font-family: Consolas, Monaco, "Courier New", monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
