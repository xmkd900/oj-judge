<template>
  <div id="questionsView">
    <a-form :model="searchParams" layout="inline">
      <a-form-item field="title" label="名称" style="min-width: 240px">
        <a-input v-model="searchParams.title" placeholder="请输入名称" />
      </a-form-item>
      <a-form-item field="tags" label="标签" style="min-width: 240px">
        <a-select
          v-model="searchParams.tags"
          :options="tagOptions"
          placeholder="选择标签（可多选）"
          multiple
          allow-clear
          :style="{ width: '320px' }"
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="doSubmit">提交</a-button>
      </a-form-item>
    </a-form>
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
      @sorter-change="onSorterChange"
    >
      <template #tags="{ record }">
        <a-space wrap>
          <a-tag v-for="(tag, index) of record.tags" :key="index" color="green"
            >{{ tag }}
          </a-tag>
        </a-space>
      </template>
      <template #acceptedRate="{ record }">
        {{
          acceptedRate(record)
        }}
      </template>
      <template #createTime="{ record }">
        {{ formatDate(record.createTime) }}
      </template>
      <template #optional="{ record }">
        <a-space>
          <a-button type="primary" @click="toQuestionPage(record)">
            做题
          </a-button>
        </a-space>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watchEffect } from "vue";
import * as questionApi from "@/api/question";
import { QuestionQueryRequest, QuestionVO, TagVO } from "@/api/question";
import { formatDate } from "@/utils/date";
import message from "@arco-design/web-vue/es/message";
import { useRouter } from "vue-router";

/** 标签下拉的选项，带上使用次数方便用户判断热度 */
const tagList = ref<TagVO[]>([]);
const tagOptions = computed(() =>
  tagList.value.map((item) => ({
    label: `${item.tag} (${item.count})`,
    value: item.tag,
  }))
);

const loadTags = async () => {
  try {
    tagList.value = await questionApi.listAllTags();
  } catch {
    // 标签拉不到不影响题目列表本身，静默降级：用户暂时看不到选项而已
    tagList.value = [];
  }
};

onMounted(loadTags);

const tableRef = ref();

const dataList = ref<QuestionVO[]>([]);
const total = ref(0);
const searchParams = ref<QuestionQueryRequest>({
  title: "",
  tags: [],
  pageSize: 8,
  current: 1,
  // 显式声明排序：最新创建的排最前面。
  // 后端默认也是这个，但写在这里能让排序意图在代码和网络面板里都看得见。
  sortField: "createTime",
  sortOrder: "descend",
});

const loadData = async () => {
  try {
    const data = await questionApi.listQuestionVoByPage(searchParams.value);
    dataList.value = data.records;
    total.value = data.total;
  } catch (error: any) {
    message.error("加载失败：" + (error?.message ?? "请稍后重试"));
  }
};

/**
 * 监听 searchParams，改变时重新加载。
 * watchEffect 会立即执行一次，所以不需要再额外写 onMounted，否则首屏会重复请求两次。
 */
watchEffect(() => {
  loadData();
});

/**
 * 通过率展示。
 *
 * ⚠️ 之前写成 `${acceptedNum / submitNum}%`，得到的是 0.9 就拼上 %，
 * 于是 90% 会显示成 "0.9%" —— 差了 100 倍。必须 *100。
 */
const acceptedRate = (record: QuestionVO) => {
  if (!record.submitNum) {
    return "暂无数据";
  }
  const rate = Math.round(((record.acceptedNum ?? 0) / record.submitNum) * 100);
  return `${rate}% (${record.acceptedNum}/${record.submitNum})`;
};

const columns = [
  {
    title: "题号",
    dataIndex: "id",
    sortable: {
      sortDirections: ["ascend", "descend"],
    },
  },
  {
    title: "题目名称",
    dataIndex: "title",
  },
  {
    title: "标签",
    slotName: "tags",
  },
  {
    title: "通过率",
    slotName: "acceptedRate",
  },
  {
    title: "创建时间",
    dataIndex: "createTime",
    slotName: "createTime",
    sortable: {
      sortDirections: ["ascend", "descend"],
    },
  },
  {
    slotName: "optional",
  },
];

const onPageChange = (page: number) => {
  searchParams.value = {
    ...searchParams.value,
    current: page,
  };
};

/**
 * 点击表头排序。
 * direction 为空表示取消排序，这时退回默认的"创建时间倒序"。
 * 注意 sortField 只能取后端白名单内的值（id / title / createTime / updateTime /
 * submitNum / acceptedNum），否则后端会忽略。
 */
const onSorterChange = (dataIndex: string, direction: string) => {
  searchParams.value = {
    ...searchParams.value,
    current: 1,
    sortField: direction ? dataIndex : "createTime",
    sortOrder: direction || "descend",
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
#questionsView {
  max-width: 1280px;
  margin: 0 auto;
}
</style>
