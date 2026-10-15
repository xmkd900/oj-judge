<template>
  <div
    id="code-editor"
    ref="codeEditorRef"
    style="min-height: 400px; height: 60vh"
  />
  <!--  <a-button @click="fillValue">填充值</a-button>-->
</template>

<script setup lang="ts">
// 必须在 monaco-editor 之前引入，用于注册 Web Worker
import "@/monacoWorkers";
import * as monaco from "monaco-editor";
import { onMounted, ref, toRaw, watch } from "vue";

/**
 * 定义组件属性类型
 */
interface Props {
  value: string;
  language?: string;
  handleChange: (v: string) => void;
}

/**
 * 给组件指定初始值
 */
const props = withDefaults(defineProps<Props>(), {
  value: () => "",
  language: () => "java",
  handleChange: (v: string) => {
    console.log(v);
  },
});

const codeEditorRef = ref();
const codeEditor = ref();

// const fillValue = () => {
//   if (!codeEditor.value) {
//     return;
//   }
//   // 改变值
//   toRaw(codeEditor.value).setValue("新的值");
// };

watch(
  () => props.language,
  () => {
    if (codeEditor.value) {
      monaco.editor.setModelLanguage(
        toRaw(codeEditor.value).getModel(),
        props.language
      );
    }
  }
);

/**
 * 外部 value 变化时同步进编辑器。
 *
 * <p>为什么必须监听：monaco 只在 create 时用 props.value 初始化一次，
 * 之后外部改值编辑器是不知道的。切换编程语言要换代码模板时，
 * 就会一直停在旧语言的代码上。
 *
 * <p>⚠️ 一定要先比较再 setValue，否则会死循环：
 * setValue 触发 onDidChangeModelContent → handleChange 通知父组件 →
 * props.value 变化 → 又 setValue ...
 */
watch(
  () => props.value,
  (newValue) => {
    if (!codeEditor.value) {
      return;
    }
    const editor = toRaw(codeEditor.value);
    if (editor.getValue() !== (newValue ?? "")) {
      editor.setValue(newValue ?? "");
    }
  }
);

onMounted(() => {
  if (!codeEditorRef.value) {
    return;
  }
  // Hover on each property to see its docs!
  codeEditor.value = monaco.editor.create(codeEditorRef.value, {
    value: props.value,
    language: props.language,
    automaticLayout: true,
    colorDecorators: true,
    minimap: {
      enabled: true,
    },
    readOnly: false,
    theme: "vs-dark",
    // lineNumbers: "off",
    // roundedSelection: false,
    // scrollBeyondLastLine: false,
  });

  // 编辑 监听内容变化
  codeEditor.value.onDidChangeModelContent(() => {
    props.handleChange(toRaw(codeEditor.value).getValue());
  });
});
</script>

<style scoped></style>
