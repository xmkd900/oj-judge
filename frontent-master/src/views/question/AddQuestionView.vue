<template>
  <div id="addQuestionView">
    <h2>创建题目</h2>
    <a-form :model="form" label-align="left">
      <a-form-item field="title" label="标题">
        <a-input v-model="form.title" placeholder="请输入标题" />
      </a-form-item>
      <!--
        标签用「可搜 + 可新建」的多选下拉，而不是 a-input-tag。

        为什么必须换：a-input-tag 要**按回车**才会把输入框里的文字变成一个标签。
        打完字直接点"提交"，那段文字还留在输入框里、从来没进数组，
        于是题建好了但一个标签都没存（表现就是浏览页看不到标签）。

        a-select 的 allow-create 既能点选已有标签，也能手打新建，
        且选项是从 /api/question/tags 拉的全站标签（带使用次数）。
      -->
      <a-form-item field="tags" label="标签">
        <a-select
          v-model="form.tags"
          :options="tagOptions"
          placeholder="选择已有标签，或直接输入新标签后回车"
          multiple
          allow-search
          allow-create
          allow-clear
          :style="{ width: '480px' }"
        />
      </a-form-item>
      <a-form-item field="content" label="题目内容">
        <MdEditor :value="form.content" :handle-change="onContentChange" />
      </a-form-item>

      <!--
        示例（样例）：公开给做题的人看，对应洛谷的"输入样例 / 输出样例"。
        和下面的"测试用例配置"是两回事 —— 测试用例保密，用来真正判分。
      -->
      <a-form-item
        label="示例"
        :content-flex="false"
        :merge-props="false"
        extra="会展示在题面下方，帮做题的人理解输入/输出格式。建议 1~3 个"
      >
        <a-space direction="vertical" style="min-width: 640px">
          <a-form-item
            v-for="(sample, index) of form.samples"
            :key="index"
            no-style
          >
            <a-space direction="vertical" style="min-width: 640px">
              <a-form-item
                :field="`form.samples[${index}].input`"
                :label="`输入样例-${index}`"
              >
                <a-textarea
                  v-model="sample.input"
                  placeholder="请输入输入样例"
                  :auto-size="{ minRows: 2, maxRows: 6 }"
                />
              </a-form-item>
              <a-form-item
                :field="`form.samples[${index}].output`"
                :label="`输出样例-${index}`"
              >
                <a-textarea
                  v-model="sample.output"
                  placeholder="请输入输出样例"
                  :auto-size="{ minRows: 2, maxRows: 6 }"
                />
              </a-form-item>
              <a-form-item
                :field="`form.samples[${index}].explanation`"
                :label="`样例说明-${index}`"
              >
                <a-input
                  v-model="sample.explanation"
                  placeholder="可空，比如：1 和 2 相加得到 3"
                />
              </a-form-item>
            </a-space>
          </a-form-item>
        </a-space>
        <a-space style="margin-top: 8px">
          <a-button @click="addSample">添加示例</a-button>
          <a-button
            status="danger"
            :disabled="form.samples.length <= 1"
            @click="removeSample"
          >
            删除最后一个示例
          </a-button>
        </a-space>
      </a-form-item>

      <a-form-item field="answer" label="答案">
        <MdEditor :value="form.answer" :handle-change="onAnswerChange" />
      </a-form-item>
      <a-form-item label="判题配置" :content-flex="false" :merge-props="false">
        <a-space direction="vertical" style="min-width: 480px">
          <a-form-item field="judgeConfig.timeLimit" label="时间限制(ms)">
            <a-input-number
              v-model="form.judgeConfig.timeLimit"
              placeholder="请输入时间限制"
              mode="button"
              min="0"
              size="large"
            />
          </a-form-item>
          <!--
            表单里以 MB 为单位填写（人看得懂），提交前在 buildPayload 里转成字节。
            后端存的单位是字节 —— 256MB 是 268435456，让人直接填这个数字太反人类。
          -->
          <a-form-item field="judgeConfig.memoryLimit" label="内存限制(MB)">
            <a-input-number
              v-model="form.judgeConfig.memoryLimit"
              placeholder="请输入内存限制"
              mode="button"
              min="0"
              size="large"
            />
          </a-form-item>
          <a-form-item field="judgeConfig.stackLimit" label="堆栈限制(MB)">
            <a-input-number
              v-model="form.judgeConfig.stackLimit"
              placeholder="请输入堆栈限制"
              mode="button"
              min="0"
              size="large"
            />
          </a-form-item>
        </a-space>
      </a-form-item>
      <a-form-item
        label="测试用例配置"
        :content-flex="false"
        :merge-props="false"
      >
        <a-form-item
          v-for="(judgeCaseItem, index) of form.judgeCase"
          :key="index"
          no-style
        >
          <a-space direction="vertical" style="min-width: 640px">
            <!--
              用例必须是多行输入框。
              之前是 a-input（单行），于是"第一行 n target、第二行 n 个数"
              这种题根本没法配用例，只能把所有数据挤成一行 ——
              而做题的人按题面写逐行读的代码就会读不到数据，被冤枉成运行错误。
            -->
            <a-form-item
              :field="`form.judgeCase[${index}].input`"
              :label="`输入用例-${index}`"
              :key="index"
            >
              <a-textarea
                v-model="judgeCaseItem.input"
                placeholder="请输入测试输入用例，支持多行（按题目要求的输入格式填写）"
                :auto-size="{ minRows: 2, maxRows: 8 }"
              />
            </a-form-item>
            <a-form-item
              :field="`form.judgeCase[${index}].output`"
              :label="`输出用例-${index}`"
              :key="index"
            >
              <a-textarea
                v-model="judgeCaseItem.output"
                placeholder="请输入测试输出用例，支持多行"
                :auto-size="{ minRows: 2, maxRows: 8 }"
              />
            </a-form-item>
            <a-button status="danger" @click="handleDelete(index)">
              删除
            </a-button>
          </a-space>
        </a-form-item>
        <div style="margin-top: 32px">
          <a-button @click="handleAdd" type="outline" status="success"
            >新增测试用例
          </a-button>
        </div>
      </a-form-item>
      <div style="margin-top: 16px" />
      <a-form-item>
        <a-button type="primary" style="min-width: 200px" @click="doSubmit"
          >提交
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import MdEditor from "@/components/MdEditor.vue";
import * as questionApi from "@/api/question";
import { Question, TagVO } from "@/api/question";
import message from "@arco-design/web-vue/es/message";
import { useRoute, useRouter } from "vue-router";
import { bytesToMb, mbToBytes } from "@/utils/format";

const route = useRoute();
const router = useRouter();
// 如果页面地址包含 update，视为更新页面
const updatePage = route.path.includes("update");

/**
 * 全站已用过的标签，作为下拉选项。
 * 让用户**点选**而不是凭空手打，从源头减少 "Java"/"java" 这类同义词。
 * 拉不到就静默降级成空列表 —— 只是没得选，不至于没法出题。
 */
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
    tagList.value = [];
  }
};

/**
 * 判题配置默认值。
 *
 * ⚠️ 单位说明（后端存的单位）：
 *   timeLimit   —— 毫秒
 *   memoryLimit —— <b>字节</b>
 *   stackLimit  —— 字节
 *
 * 但表单里让人直接填 268435456 字节太反人类，所以表单以 MB 为单位，
 * 这里的值也按 MB 写（256 MB / 0 MB），提交前由 buildPayload 转成字节。
 */
const DEFAULT_JUDGE_CONFIG = {
  memoryLimit: 256,
  stackLimit: 0,
  timeLimit: 1000,
};

const DEFAULT_JUDGE_CASE = [{ input: "", output: "" }];

const DEFAULT_SAMPLES = [{ input: "", output: "", explanation: "" }];

let form = ref({
  title: "",
  tags: [] as string[],
  answer: "",
  content: "",
  samples: [{ ...DEFAULT_SAMPLES[0] }] as {
    input?: string;
    output?: string;
    explanation?: string;
  }[],
  judgeConfig: { ...DEFAULT_JUDGE_CONFIG },
  judgeCase: [{ input: "", output: "" }],
});

const addSample = () => {
  form.value.samples.push({ input: "", output: "", explanation: "" });
};

const removeSample = () => {
  if (form.value.samples.length > 1) {
    form.value.samples.pop();
  }
};

/**
 * 换行符统一成 \n。
 *
 * Windows 上从别处粘贴进来的文本可能是 \r\n，残留的 \r 会留在行尾 ——
 * 用户代码若不做 strip，读入的就是 "3\r"，比对或解析都可能出错。
 */
const normalizeNewlines = (text?: string) =>
  text?.replace(/\r\n/g, "\n").replace(/\r/g, "\n");

/**
 * 提交前的收尾处理：
 * 1. 内存单位从 MB 转回字节（不转的话后端收到 "256" 会当成 256 字节 —— 比 1KB 还小，必超时）
 * 2. 用例和示例的换行符统一成 \n
 */
const buildPayload = () => ({
  ...form.value,
  judgeCase: form.value.judgeCase.map((item) => ({
    ...item,
    input: normalizeNewlines(item.input),
    output: normalizeNewlines(item.output),
  })),
  samples: form.value.samples.map((item) => ({
    ...item,
    input: normalizeNewlines(item.input),
    output: normalizeNewlines(item.output),
  })),
  judgeConfig: {
    ...form.value.judgeConfig,
    memoryLimit: mbToBytes(form.value.judgeConfig.memoryLimit),
    stackLimit: mbToBytes(form.value.judgeConfig.stackLimit),
  },
});

/** JSON 解析失败时退回默认值，避免一个字段坏掉整页打不开 */
const safeParse = (text: string, fallback: unknown) => {
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
};

/**
 * 根据题目 id 获取老的数据
 */
const loadData = async () => {
  const id = route.query.id;
  if (!id) {
    return;
  }
  try {
    const data: Question = await questionApi.getQuestionById(id);
    // judgeCase / judgeConfig / samples 在库里是 JSON 字符串，转成 js 对象才能编辑。
    // 标签不一样：它已经从 question_tag 关联表查出来挂在 tagList 上，直接就是数组。
    const cfg = data.judgeConfig
      ? safeParse(data.judgeConfig, DEFAULT_JUDGE_CONFIG)
      : DEFAULT_JUDGE_CONFIG;
    form.value = {
      ...data,
      judgeCase: data.judgeCase
        ? safeParse(data.judgeCase, DEFAULT_JUDGE_CASE)
        : DEFAULT_JUDGE_CASE,
      samples: data.samples
        ? safeParse(data.samples, DEFAULT_SAMPLES)
        : DEFAULT_SAMPLES,
      // 后端存字节，表单以 MB 展示，这里换算一次
      judgeConfig: {
        timeLimit: cfg.timeLimit ?? DEFAULT_JUDGE_CONFIG.timeLimit,
        memoryLimit:
          bytesToMb(cfg.memoryLimit) ?? DEFAULT_JUDGE_CONFIG.memoryLimit,
        stackLimit: bytesToMb(cfg.stackLimit) ?? DEFAULT_JUDGE_CONFIG.stackLimit,
      },
      tags: data.tagList ?? [],
    } as any;
  } catch (error: any) {
    message.error("加载失败：" + (error?.message ?? "请稍后重试"));
  }
};

onMounted(() => {
  loadTags();
  loadData();
});

const doSubmit = async () => {
  try {
    if (updatePage) {
      // 走 /edit（作者本人或管理员）而不是 /update（仅管理员）：
      // 否则普通作者改自己创建的题会被 40101 拒掉
      await questionApi.editQuestion({
        ...buildPayload(),
        id: Number(route.query.id),
      });
      message.success("更新成功");
      // 可能新建了标签，刷新一下选项，下次就能直接选到
      loadTags();
    } else {
      const id = await questionApi.addQuestion(buildPayload());
      message.success("创建成功");
      // 可能新建了标签，刷新一下选项
      loadTags();
      if (id) {
        // 创建完直接跳到编辑页，方便继续补充测试用例
        router.replace({ path: "/update/question", query: { id } });
      }
    }
  } catch (error: any) {
    message.error((updatePage ? "更新失败：" : "创建失败：") + (error?.message ?? "请稍后重试"));
  }
};

/**
 * 新增判题用例
 */
const handleAdd = () => {
  form.value.judgeCase.push({
    input: "",
    output: "",
  });
};

/**
 * 删除判题用例
 */
const handleDelete = (index: number) => {
  form.value.judgeCase.splice(index, 1);
};

const onContentChange = (value: string) => {
  form.value.content = value;
};

const onAnswerChange = (value: string) => {
  form.value.answer = value;
};
</script>

<style scoped>
#addQuestionView {
}
</style>
