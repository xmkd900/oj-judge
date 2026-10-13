<template>
  <div id="manageQuestionView">
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
      <template #optional="{ record }">
        <a-space>
          <a-button type="primary" @click="doUpdate(record)"> 修改</a-button>
          <a-button status="danger" @click="doDelete(record)">删除</a-button>
        </a-space>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, watchEffect } from "vue";
import * as questionApi from "@/api/question";
import { Question, QuestionQueryRequest } from "@/api/question";
import message from "@arco-design/web-vue/es/message";
import { useRouter } from "vue-router";

const tableRef = ref();

const dataList = ref<Question[]>([]);
const total = ref(0);
const searchParams = ref<QuestionQueryRequest>({
  pageSize: 10,
  current: 1,
});

const loadData = async () => {
  try {
    const data = await questionApi.listQuestionByPage(searchParams.value);
    dataList.value = data.records;
    total.value = data.total;
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

const columns = [
  {
    title: "id",
    dataIndex: "id",
  },
  {
    title: "标题",
    dataIndex: "title",
  },
  {
    title: "内容",
    dataIndex: "content",
  },
  {
    title: "标签",
    dataIndex: "tags",
  },
  {
    title: "答案",
    dataIndex: "answer",
  },
  {
    title: "提交数",
    dataIndex: "submitNum",
  },
  {
    title: "通过数",
    dataIndex: "acceptedNum",
  },
  {
    title: "判题配置",
    dataIndex: "judgeConfig",
  },
  {
    title: "判题用例",
    dataIndex: "judgeCase",
  },
  {
    title: "用户id",
    dataIndex: "userId",
  },
  {
    title: "创建时间",
    dataIndex: "createTime",
  },
  {
    title: "操作",
    slotName: "optional",
  },
];

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page,
  };
};

const doDelete = async (question: Question) => {
  if (!question.id) {
    return;
  }
  try {
    await questionApi.deleteQuestion(question.id);
    message.success("删除成功");
    loadData();
  } catch (error: any) {
    message.error("删除失败：" + (error?.message ?? "请稍后重试"));
  }
};

const router = useRouter();

const doUpdate = (question: Question) => {
  router.push({
    path: "/update/question",
    query: {
      id: question.id,
    },
  });
};
</script>

<style scoped>
#manageQuestionView {
}
</style>
