<template>
  <div id="viewQuestionView">
    <a-row :gutter="[24, 24]">
      <a-col :md="12" :xs="24">
        <a-tabs default-active-key="question" @change="onTabChange">
          <a-tab-pane key="question" title="题目">
            <a-card v-if="question" :title="question.title">
              <a-descriptions
                title="判题条件"
                :column="{ xs: 1, md: 2, lg: 3 }"
              >
                <a-descriptions-item label="时间限制">
                  {{ formatTime(question.judgeConfig?.timeLimit) }}
                </a-descriptions-item>
                <a-descriptions-item label="内存限制">
                  {{ formatMemory(question.judgeConfig?.memoryLimit) }}
                </a-descriptions-item>
                <a-descriptions-item label="堆栈限制">
                  {{ formatMemory(question.judgeConfig?.stackLimit) }}
                </a-descriptions-item>
              </a-descriptions>

              <MdViewer :value="question.content || ''" />

              <!-- 示例（样例）：洛谷题面里的输入样例 / 输出样例，帮做题的人理解格式 -->
              <div v-if="samples.length > 0" class="samples-block">
                <h3 class="samples-title">示例</h3>
                <div
                  v-for="(sample, index) of samples"
                  :key="index"
                  class="sample-item"
                >
                  <div class="sample-head">
                    示例 {{ index + 1 }}
                    <span v-if="sample.explanation" class="sample-expl">
                      {{ sample.explanation }}
                    </span>
                  </div>
                  <a-row :gutter="16">
                    <a-col :span="12">
                      <div class="sample-label">输入</div>
                      <pre class="sample-code">{{ sample.input ?? "" }}</pre>
                    </a-col>
                    <a-col :span="12">
                      <div class="sample-label">输出</div>
                      <pre class="sample-code">{{ sample.output ?? "" }}</pre>
                    </a-col>
                  </a-row>
                </div>
              </div>

              <template #extra>
                <a-space wrap>
                  <a-tag
                    v-for="(tag, index) of question.tags"
                    :key="index"
                    color="green"
                    >{{ tag }}
                  </a-tag>
                </a-space>
              </template>
            </a-card>
          </a-tab-pane>
          <a-tab-pane key="comment" title="评论" disabled> 评论区</a-tab-pane>
          <a-tab-pane key="answer" title="答案">
            <!--
              题解只对作者本人和管理员开放。
              这里不在前端判断权限，而是直接请求后端 —— 权限以后端为准，
              免得前端判断写错导致泄露。切到这个 tab 时才懒加载。
            -->
            <a-spin v-if="answerLoading" tip="加载中" />
            <template v-else-if="answer">
              <a-alert type="warning" style="margin-bottom: 12px">
                题解仅你（作者本人或管理员）可见，做题的人看不到
              </a-alert>
              <MdViewer :value="answer" />
            </template>
            <a-empty v-else :description="answerError || '暂无题解'" />
          </a-tab-pane>
        </a-tabs>
      </a-col>
      <a-col :md="12" :xs="24">
        <a-form :model="form" layout="inline">
          <a-form-item
            field="language"
            label="编程语言"
            style="min-width: 240px"
          >
            <a-select
              v-model="form.language"
              :style="{ width: '320px' }"
              placeholder="选择编程语言"
            >
              <a-option>java</a-option>
              <a-option>cpp</a-option>
              <a-option>go</a-option>
              <a-option>html</a-option>
            </a-select>
          </a-form-item>
        </a-form>
        <CodeEditor
          :value="form.code as string"
          :language="form.language"
          :handle-change="changeCode"
        />
        <a-divider size="0" />
        <a-button type="primary" style="min-width: 200px" @click="doSubmit">
          提交代码
        </a-button>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import message from "@arco-design/web-vue/es/message";
import CodeEditor from "@/components/CodeEditor.vue";
import MdViewer from "@/components/MdViewer.vue";
import * as questionApi from "@/api/question";
import {
  QuestionSubmitAddRequest,
  QuestionVO,
  SampleCase,
} from "@/api/question";
import { formatMemory, formatTime } from "@/utils/format";

interface Props {
  id: string;
}

const props = withDefaults(defineProps<Props>(), {
  id: () => "",
});

const question = ref<QuestionVO>();

/** 示例（样例）。VO 里已经是解析好的数组了 */
const samples = computed<SampleCase[]>(() => question.value?.samples ?? []);

const loadData = async () => {
  try {
    question.value = await questionApi.getQuestionVoById(props.id);
  } catch (error: any) {
    message.error("加载失败：" + (error?.message ?? "请稍后重试"));
  }
};

// ------------------------------------------------------------------ 题解

const answer = ref<string>();
const answerError = ref("");
const answerLoading = ref(false);
let answerLoaded = false;

/**
 * 切到"答案"tab 时才去拉题解。
 *
 * 不去前端判断"我是不是作者"，而是直接请求后端的完整实体接口：
 * 是作者/管理员就返回 200 + 答案，否则后端返回 40101 / 40400。
 * 权限以后端为准，前端判断错了也不会泄露。
 */
const loadAnswer = async () => {
  if (answerLoaded) {
    return;
  }
  answerLoaded = true;
  answerLoading.value = true;
  try {
    const full = await questionApi.getQuestionById(props.id);
    answer.value = full.answer || "";
    if (!answer.value) {
      answerError.value = "这道题还没有填写题解";
    }
  } catch (error: any) {
    answerError.value = "题解仅题目作者本人或管理员可见";
  } finally {
    answerLoading.value = false;
  }
};

const onTabChange = (key: string | number) => {
  if (key === "answer") {
    loadAnswer();
  }
};

const form = ref<QuestionSubmitAddRequest>({
  language: "java",
  code: "",
});

/**
 * 提交代码。
 * 注意：判题是异步的，这里返回成功只代表"提交记录已创建"，
 * 结果要去提交记录页看，或轮询 question_submit/list/page。
 */
const doSubmit = async () => {
  if (!question.value?.id) {
    return;
  }
  try {
    await questionApi.doQuestionSubmit({
      ...form.value,
      questionId: question.value.id,
    });
    message.success("提交成功，请到提交记录页查看判题结果");
  } catch (error: any) {
    message.error("提交失败：" + (error?.message ?? "请稍后重试"));
  }
};

/**
 * 页面加载时，请求数据
 */
onMounted(() => {
  loadData();
});

const changeCode = (value: string) => {
  form.value.code = value;
};
</script>

<style>
#viewQuestionView {
  max-width: 1400px;
  margin: 0 auto;
}

#viewQuestionView .arco-space-horizontal .arco-space-item {
  margin-bottom: 0 !important;
}

/* 示例（样例）区块 */
.samples-block {
  margin-top: 20px;
}

.samples-title {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.sample-item {
  margin-bottom: 16px;
  padding: 12px;
  border: 1px solid var(--color-border-2, #e5e6eb);
  border-radius: 4px;
}

.sample-head {
  margin-bottom: 10px;
  font-weight: 600;
}

.sample-expl {
  margin-left: 8px;
  font-weight: 400;
  font-size: 13px;
  color: var(--color-text-3, #86909c);
}

.sample-label {
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--color-text-3, #86909c);
}

.sample-code {
  margin: 0;
  padding: 10px 12px;
  background: var(--color-fill-2, #f7f8fa);
  border-radius: 4px;
  font-family: Consolas, Monaco, "Courier New", monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
