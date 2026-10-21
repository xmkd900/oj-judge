import request, { BaseResponse, unwrap } from "@/api/request";

/**
 * 题目下的帖子（评论 / 讨论）接口。
 *
 * 和题目接口一样走 @/api/request 实例，所以自动带 Authorization 头。
 */

// ------------------------------------------------------------------ 类型

/** 发帖请求 */
export interface PostAddRequest {
  /** 发在哪道题下面 */
  questionId: number;
  /** 标题，可选 */
  title?: string;
  /** 正文，纯文本 */
  content: string;
}

/** 帖子列表查询 */
export interface PostQueryRequest {
  questionId?: number;
  current?: number;
  pageSize?: number;
}

/** 帖子。userName / userAvatar 是作者信息；hasThumb / own 是"和我有关"的状态 */
export interface PostVO {
  id?: number;
  questionId?: number;
  title?: string;
  content?: string;
  thumbNum?: number;
  userId?: number;
  createTime?: string;
  userName?: string;
  userAvatar?: string;
  /** 我有没有点赞过。未登录恒为 false */
  hasThumb?: boolean;
  /** 是不是我发的，决定要不要显示删除按钮 */
  own?: boolean;
}

export interface PageResult<T> {
  records: T[];
  total: number;
}

// ------------------------------------------------------------------ 接口

/** 发帖（需登录） */
export async function addPost(body: PostAddRequest): Promise<number> {
  const res = await request.post<BaseResponse<number>>("/api/post/add", body);
  return unwrap(res.data);
}

/** 删帖，本人或管理员 */
export async function deletePost(id: number): Promise<boolean> {
  const res = await request.post<BaseResponse<boolean>>("/api/post/delete", {
    id,
  });
  return unwrap(res.data);
}

/** 某道题下面的帖子列表，按时间倒序。公开接口，游客也能看 */
export async function listPostByPage(
  params: PostQueryRequest
): Promise<PageResult<PostVO>> {
  const res = await request.get<BaseResponse<PageResult<PostVO>>>(
    "/api/post/list",
    { params }
  );
  return unwrap(res.data);
}

/**
 * 点赞。幂等：重复调用不会让点赞数多加。
 *
 * 后端刻意拆成 thumb / unthumb 两个接口，而不是一个"切换"接口 ——
 * 切换不是幂等操作，并发下会互相抵消（点两次等于赞了又取消）。
 */
export async function thumbPost(postId: number): Promise<boolean> {
  const res = await request.post<BaseResponse<boolean>>(
    `/api/post/thumb?postId=${postId}`
  );
  return unwrap(res.data);
}

/** 取消点赞。同样幂等 */
export async function unthumbPost(postId: number): Promise<boolean> {
  const res = await request.post<BaseResponse<boolean>>(
    `/api/post/unthumb?postId=${postId}`
  );
  return unwrap(res.data);
}
