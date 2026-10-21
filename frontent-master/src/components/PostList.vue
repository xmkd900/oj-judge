<template>
  <div class="post-area">
    <!-- 发帖框。未登录时不显示，避免出现"点了发布才告诉我要登录"的体验 -->
    <div v-if="loggedIn" class="post-editor">
      <a-input
        v-model="newTitle"
        placeholder="标题（可选）"
        :max-length="128"
        allow-clear
        class="post-title-input"
      />
      <a-textarea
        v-model="newContent"
        placeholder="说说你的思路，或者哪里卡住了…"
        :max-length="5000"
        :auto-size="{ minRows: 3, maxRows: 8 }"
        allow-clear
      />
      <div class="post-editor-footer">
        <span class="post-hint">{{ newContent.length }} / 5000</span>
        <a-button
          type="primary"
          :loading="posting"
          :disabled="!newContent.trim()"
          @click="doAddPost"
        >
          发布
        </a-button>
      </div>
    </div>
    <a-alert v-else>登录后才能发帖和点赞</a-alert>

    <a-divider size="0" />

    <!-- 帖子列表 -->
    <a-spin v-if="loading" tip="加载中" style="width: 100%" />
    <template v-else>
      <a-empty v-if="posts.length === 0" description="还没有人发言，来说两句？" />
      <div v-else class="post-list">
        <div v-for="post of posts" :key="post.id" class="post-item">
          <div class="post-head">
            <a-avatar :size="28" :image-url="post.userAvatar">
              {{ (post.userName ?? "?").charAt(0) }}
            </a-avatar>
            <span class="post-author">{{ post.userName ?? "匿名" }}</span>
            <span class="post-time">{{ formatDate(post.createTime) }}</span>
            <a-popconfirm
              v-if="post.own || isAdmin"
              content="确定删除这条？"
              @ok="() => doDelete(post)"
            >
              <a-button size="mini" status="danger" type="text">删除</a-button>
            </a-popconfirm>
          </div>

          <div v-if="post.title" class="post-title">{{ post.title }}</div>
          <div class="post-content">{{ post.content }}</div>

          <div class="post-foot">
            <a-button
              size="mini"
              :type="post.hasThumb ? 'primary' : 'outline'"
              :status="post.hasThumb ? 'success' : 'normal'"
              :disabled="!loggedIn"
              @click="() => doThumb(post)"
            >
              {{ post.hasThumb ? "已赞" : "点赞" }} {{ post.thumbNum ?? 0 }}
            </a-button>
          </div>
        </div>
      </div>

      <div v-if="total > pageSize" class="post-pager">
        <a-pagination
          :total="total"
          :current="current"
          :page-size="pageSize"
          size="small"
          @change="onPageChange"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import message from "@arco-design/web-vue/es/message";
import { useStore } from "vuex";
import * as postApi from "@/api/post";
import { PostVO } from "@/api/post";
import ACCESS_ENUM from "@/access/accessEnum";

interface Props {
  /** 题目 id */
  questionId: number;
}

const props = withDefaults(defineProps<Props>(), {
  questionId: () => 0,
});

const store = useStore();
const loggedIn = computed(() => {
  const role = store.state.user?.loginUser?.userRole;
  return !!role && role !== ACCESS_ENUM.NOT_LOGIN;
});

const isAdmin = computed(() => {
  const role = store.state.user?.loginUser?.userRole;
  return role === ACCESS_ENUM.ADMIN;
});

const posts = ref<PostVO[]>([]);
const total = ref(0);
const current = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const posting = ref(false);

const newTitle = ref("");
const newContent = ref("");

const loadPosts = async () => {
  loading.value = true;
  try {
    const res = await postApi.listPostByPage({
      questionId: props.questionId,
      current: current.value,
      pageSize: pageSize.value,
    });
    posts.value = res.records ?? [];
    total.value = res.total ?? 0;
  } catch (error: any) {
    message.error("加载评论失败：" + (error?.message ?? "请稍后重试"));
  } finally {
    loading.value = false;
  }
};

/**
 * 发帖后回到第一页 —— 新帖是按时间倒序排的，
 * 停在原来的页码上会看不到自己刚发的内容。
 */
const doAddPost = async () => {
  if (!newContent.value.trim()) {
    return;
  }
  posting.value = true;
  try {
    await postApi.addPost({
      questionId: props.questionId,
      title: newTitle.value.trim() || undefined,
      content: newContent.value.trim(),
    });
    newTitle.value = "";
    newContent.value = "";
    current.value = 1;
    await loadPosts();
    message.success("发布成功");
  } catch (error: any) {
    message.error("发布失败：" + (error?.message ?? "请稍后重试"));
  } finally {
    posting.value = false;
  }
};

const doDelete = async (post: PostVO) => {
  if (!post.id) return;
  try {
    await postApi.deletePost(post.id);
    message.success("已删除");
    await loadPosts();
  } catch (error: any) {
    message.error("删除失败：" + (error?.message ?? "请稍后重试"));
  }
};

/**
 * 点赞 / 取消。
 *
 * 按当前状态决定调哪个接口，而不是让后端去"切换" ——
 * 切换不是幂等操作，用户手抖点两下就会赞了又取消。
 *
 * 改完直接更新本地状态，不重新拉整个列表：
 * 点赞是很轻的操作，为此刷新一整页不划算。
 */
const doThumb = async (post: PostVO) => {
  if (!post.id) return;
  const wasThumbed = !!post.hasThumb;
  try {
    if (wasThumbed) {
      await postApi.unthumbPost(post.id);
      post.hasThumb = false;
      post.thumbNum = Math.max((post.thumbNum ?? 1) - 1, 0);
    } else {
      await postApi.thumbPost(post.id);
      post.hasThumb = true;
      post.thumbNum = (post.thumbNum ?? 0) + 1;
    }
  } catch (error: any) {
    message.error("操作失败：" + (error?.message ?? "请稍后重试"));
    // 失败了就恢复原状，避免界面显示的和实际不一致
    post.hasThumb = wasThumbed;
  }
};

const onPageChange = (page: number) => {
  current.value = page;
  loadPosts();
};

const formatDate = (value?: string) => {
  if (!value) return "";
  // 后端返回的是 LocalDateTime，前端拿到形如 "2026-10-20T16:55:54"
  return String(value).replace("T", " ").substring(0, 16);
};

onMounted(loadPosts);
watch(() => props.questionId, loadPosts);
</script>

<style scoped>
.post-area {
  padding: 4px 0;
}
.post-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.post-editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.post-hint {
  color: var(--color-text-3);
  font-size: 12px;
}
.post-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.post-item {
  border-bottom: 1px solid var(--color-border-2);
  padding-bottom: 12px;
}
.post-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.post-author {
  font-weight: 500;
}
.post-time {
  color: var(--color-text-3);
  font-size: 12px;
  margin-right: auto;
}
.post-title {
  font-weight: 500;
  margin-top: 8px;
}
.post-content {
  margin-top: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}
.post-foot {
  margin-top: 8px;
}
.post-pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
