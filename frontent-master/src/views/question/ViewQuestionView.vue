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
          <a-tab-pane key="comment" title="评论">
            <PostList :question-id="Number(props.id)" />
          </a-tab-pane>
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
              @change="onLanguageChange"
            >
              <a-option>java</a-option>
              <a-option>cpp</a-option>
              <a-option>go</a-option>
              <a-option>python</a-option>
            </a-select>
          </a-form-item>
        </a-form>
        <!-- 语言约定提示：最常见的编译错误就是入口类名不对 -->
        <a-alert v-if="languageTip" type="normal" style="margin-bottom: 8px">
          {{ languageTip }}
        </a-alert>
        <CodeEditor
          :value="form.code as string"
          :language="form.language"
          :handle-change="changeCode"
        />
        <a-divider size="0" />
        <a-button type="primary" style="min-width: 200px" @click="doSubmit">
          提交代码
        </a-button>
        <!-- 提交后直接把结论显示在这里，不用来回切页面 -->
        <div v-if="lastResult" style="margin-top: 16px">
          <a-alert :type="lastResult.ok ? 'success' : 'error'">
            {{ lastResult.message }}
            <span v-if="lastResult.time != null">
              · 耗时 {{ formatTime(lastResult.time) }}
            </span>
            <span v-if="lastResult.memory != null">
              · 内存 {{ formatMemory(lastResult.memory) }}
            </span>
          </a-alert>
          <a-collapse v-if="lastResult.errorMessage" style="margin-top: 8px">
            <a-collapse-item header="查看详细错误信息" :key="'err'">
              <pre class="error-detail">{{ lastResult.errorMessage }}</pre>
            </a-collapse-item>
          </a-collapse>
        </div>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import message from "@arco-design/web-vue/es/message";
import CodeEditor from "@/components/CodeEditor.vue";
import MdViewer from "@/components/MdViewer.vue";
import PostList from "@/components/PostList.vue";
import * as questionApi from "@/api/question";
import {
  QuestionSubmitAddRequest,
  QuestionVO,
  SampleCase,
  getCodeTemplate,
  LANGUAGE_TIPS,
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
  // 进来就给一份能直接跑的模板。
  // 空编辑器最容易导致的问题：用户从别处复制 class Solution 过来，
  // 文件名对不上，javac 直接编译错误，而用户看不到具体报错会一头雾水。
  code: getCodeTemplate("java"),
});

/** 当前语言的编码约定提示 */
const languageTip = computed(() => LANGUAGE_TIPS[form.value.language ?? ""] ?? "");

/**
 * 切换语言时，如果编辑器里还是原来的模板（即用户没写过东西），
 * 就换成新语言的模板；已经写了代码就别覆盖，否则会把用户的劳动成果冲掉。
 */
// 放在 onLanguageChange 之前声明：const/let 有暂时性死区，
// 虽然在函数里引用是安全的（调用时才求值），但顺序写对更不容易踩坑
let previousLanguage = "java";

const onLanguageChange = (language: string) => {
  const current = form.value.code?.trim() ?? "";
  const isUntouched =
    current === "" || current === getCodeTemplate(previousLanguage).trim();
  if (isUntouched) {
    form.value.code = getCodeTemplate(language);
  } else {
    // 必须告诉用户为什么没变，否则他会以为切换功能坏了
    message.info("检测到你已修改代码，已保留原内容，未切换模板");
  }
  previousLanguage = language;
};

/** 最近一次提交的结果，显示在编辑器下方 */
const lastResult = ref<{
  ok: boolean;
  message: string;
  time?: number | null;
  memory?: number | null;
  errorMessage?: string;
}>();
let watchingSubmitId: number | undefined;

/**
 * 提交代码。
 *
 * <p>提交接口只返回 submitId（判题是异步的），所以这里要轮询拿结果。
 * 轮询到 status >= 2（成功/失败）就把结论显示出来 ——
 * 尤其是编译错误，必须把 javac 的原始报错给用户看，
 * 否则他只知道"编译错误"，不知道是类名不对还是少了分号。
 */
const doSubmit = async () => {
  if (!question.value?.id) {
    return;
  }
  lastResult.value = undefined;
  try {
    const submitId = await questionApi.doQuestionSubmit({
      ...form.value,
      questionId: question.value.id,
    });
    message.success("提交成功，正在判题…");
    watchingSubmitId = submitId;
    await pollResult(submitId);
  } catch (error: any) {
    message.error("提交失败：" + (error?.message ?? "请稍后重试"));
  }
};

/**
 * 轮询判题结果。
 *
 * 直接按 id 查这一条，不再"拉一页再本地 find" ——
 * 后端已把列表收窄为只看自己的，拉页的方式既浪费又依赖排序刚好命中。
 */
const pollResult = async (submitId: number) => {
  for (let i = 0; i < 60; i++) {
    try {
      const rec = await questionApi.getQuestionSubmitById(submitId);
      if ((rec.status ?? 0) >= 2) {
        lastResult.value = {
          ok: rec.judgeInfo?.message === "Accepted",
          message: rec.judgeInfo?.message ?? "未知",
          time: rec.judgeInfo?.time,
          memory: rec.judgeInfo?.memory,
          errorMessage: rec.judgeInfo?.errorMessage,
        };
        return;
      }
    } catch (e) {
      // 事务刚提交的瞬间可能还没查到，忽略后继续轮询
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  lastResult.value = { ok: false, message: "判题超时，请到提交记录页查看" };
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
