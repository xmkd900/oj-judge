import request, { BaseResponse, unwrap } from "@/api/request";

/**
 * 题目相关接口。
 *
 * 两个关键点：
 * 1. 全部走 @/api/request 实例，因此自动带 Authorization 头、401 时自动刷新令牌。
 *    （之前这些页面直接用 generated/ 里的服务，走的是裸 axios，token 根本带不上）
 * 2. 类型按「后端将要实现的契约」定义，不依赖 generated/ 里那份过时的模型。
 */

// ------------------------------------------------------------------ 类型

/** 判题配置。timeLimit 单位毫秒，memoryLimit 单位字节 */
export interface JudgeConfig {
  timeLimit?: number;
  memoryLimit?: number;
  stackLimit?: number;
}

/** 一个判题用例。保密，只有作者本人或管理员能拿到，用来真正判定对错 */
export interface JudgeCase {
  input?: string;
  output?: string;
}

/**
 * 一道题的示例（样例），对应洛谷题面里的"输入样例 / 输出样例"。
 *
 * 它和 JudgeCase 是两回事：
 *   示例     —— 公开给所有做题的人，1~3 个，用来理解题意
 *   判题用例 —— 保密，可能几十个含边界数据，用来真正判分
 * 混用的话边界数据就泄露了，题目失去区分度。
 */
export interface SampleCase {
  input?: string;
  output?: string;
  /** 样例说明，可空。比如"1 和 2 相加得到 3" */
  explanation?: string;
}

/** 判题结果 */
export interface JudgeInfo {
  message?: string;
  /** 运行耗时（毫秒） */
  time?: number;
  /** 内存占用 */
  memory?: number;
}

/**
 * 题目脱敏视图：不含 answer、judgeCase（敏感字段，只有作者本人或管理员能拿到）。
 *
 * 字段只保留前端真正消费的那些，与 API.md 3.1 的定义严格对齐。
 * 裁剪掉的字段（userId / userVO / thumbNum / favourNum / updateTime）登记在
 * API.md 的「预留字段」表里，将来要用再加回来。
 *
 * 注意 judgeConfig 在这里是对象，在完整实体 Question 里是 JSON 字符串。
 */
export interface QuestionVO {
  id?: number;
  title?: string;
  content?: string;
  /** 示例（样例），公开 */
  samples?: SampleCase[];
  tags?: string[];
  submitNum?: number;
  acceptedNum?: number;
  judgeConfig?: JudgeConfig;
  createTime?: string;
}

/** 题目完整实体：含答案与测试用例，仅作者本人或管理员可获取 */
export interface Question {
  id?: number;
  title?: string;
  content?: string;
  /**
   * 标签数组。注意不是 JSON 字符串了 ——
   * question 表的 tags 列已删除，标签唯一来源是 question_tag 关联表，
   * 后端查询后挂在 tagList 上返回，直接就是数组。
   */
  tagList?: string[];
  answer?: string;
  submitNum?: number;
  acceptedNum?: number;
  /** 示例，JSON 字符串，前端需自行 parse */
  samples?: string;
  /** JSON 字符串 */
  judgeCase?: string;
  /** JSON 字符串 */
  judgeConfig?: string;
  thumbNum?: number;
  favourNum?: number;
  userId?: number;
  createTime?: string;
  updateTime?: string;
}

export interface QuestionQueryRequest {
  current?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: number;
  title?: string;
  tags?: string[];
}

export interface QuestionAddRequest {
  title?: string;
  content?: string;
  tags?: string[];
  answer?: string;
  judgeCase?: JudgeCase[];
  judgeConfig?: JudgeConfig;
}

export interface QuestionEditRequest extends QuestionAddRequest {
  id?: number;
}

export interface QuestionSubmitAddRequest {
  questionId?: number;
  language?: string;
  code?: string;
}

export interface QuestionSubmitQueryRequest {
  current?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  questionId?: number;
  userId?: number;
  language?: string;
  status?: number;
}

export interface QuestionSubmitVO {
  id?: number;
  questionId?: number;
  userId?: number;
  language?: string;
  /** 非本人/非管理员时为 null */
  code?: string;
  /** 0 等待中 / 1 判题中 / 2 成功 / 3 失败 */
  status?: number;
  judgeInfo?: JudgeInfo;
  createTime?: string;
}

/**
 * 分页结果。
 * MyBatis-Plus 的 Page 实际还会返回 size/current/pages/orders 等字段，
 * 但前端只用到 records 和 total，其余标为可选。
 */
export interface PageResult<T> {
  records: T[];
  total: number;
  size?: number;
  current?: number;
  pages?: number;
}

// ------------------------------------------------------------------ 接口

/** 一个标签及其使用次数 */
export interface TagVO {
  tag: string;
  count: number;
}

/**
 * 全部已使用的标签及使用次数，供题目筛选的下拉框使用。
 * 有了它就不用让用户手打标签，从源头避免 "Java"/"java" 同义词混乱。
 */
export async function listAllTags(): Promise<TagVO[]> {
  const res = await request.get<BaseResponse<TagVO[]>>("/api/question/tags");
  return unwrap(res.data);
}

/** 分页查询题目（脱敏），无需登录 */
export async function listQuestionVoByPage(
  params: QuestionQueryRequest
): Promise<PageResult<QuestionVO>> {
  const res = await request.post<BaseResponse<PageResult<QuestionVO>>>(
    "/api/question/list/page/vo",
    params
  );
  return unwrap(res.data);
}

/** 查询题目详情（脱敏） */
export async function getQuestionVoById(id: number | string): Promise<QuestionVO> {
  const res = await request.get<BaseResponse<QuestionVO>>("/api/question/get/vo", {
    params: { id },
  });
  return unwrap(res.data);
}

/** 分页查询题目（含答案），管理员 */
export async function listQuestionByPage(
  params: QuestionQueryRequest
): Promise<PageResult<Question>> {
  const res = await request.post<BaseResponse<PageResult<Question>>>(
    "/api/question/list/page",
    params
  );
  return unwrap(res.data);
}

/** 查询题目完整信息（含答案、测试用例），仅作者本人或管理员 */
export async function getQuestionById(id: number | string): Promise<Question> {
  const res = await request.get<BaseResponse<Question>>("/api/question/get", {
    params: { id },
  });
  return unwrap(res.data);
}

/** 创建题目 */
export async function addQuestion(
  payload: QuestionAddRequest
): Promise<number | undefined> {
  const res = await request.post<BaseResponse<number>>(
    "/api/question/add",
    payload
  );
  return unwrap(res.data);
}

/** 编辑题目（作者本人或管理员） */
export async function editQuestion(
  payload: QuestionEditRequest
): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>(
    "/api/question/edit",
    payload
  );
  return unwrap(res.data);
}

/** 更新题目（管理员通道，不校验归属） */
export async function updateQuestion(
  payload: QuestionEditRequest
): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>(
    "/api/question/update",
    payload
  );
  return unwrap(res.data);
}

/** 删除题目 */
export async function deleteQuestion(id: number): Promise<boolean | undefined> {
  const res = await request.post<BaseResponse<boolean>>("/api/question/delete", {
    id,
  });
  return unwrap(res.data);
}

/** 提交代码。返回提交记录 id；判题是异步的，需要轮询查结果 */
export async function doQuestionSubmit(
  payload: QuestionSubmitAddRequest
): Promise<number | undefined> {
  const res = await request.post<BaseResponse<number>>(
    "/api/question/question_submit/do",
    payload
  );
  return unwrap(res.data);
}

/** 分页查询提交记录 */
export async function listQuestionSubmitByPage(
  params: QuestionSubmitQueryRequest
): Promise<PageResult<QuestionSubmitVO>> {
  const res = await request.post<BaseResponse<PageResult<QuestionSubmitVO>>>(
    "/api/question/question_submit/list/page",
    params
  );
  return unwrap(res.data);
}

/**
 * 判题结论是否为「通过」。
 * 后端返回的 message 中英文混杂，只认 Accepted 最稳。
 */
export function isAccepted(judgeInfo?: JudgeInfo): boolean {
  return judgeInfo?.message === "Accepted";
}

/**
 * 各语言的初始代码模板。
 *
 * 为什么必须有这个：判题沙箱把源码写成固定文件名再编译，
 *   Java  -> Main.java，所以 public 类名必须是 **Main**
 *   C++   -> main.cpp，入口是 main()
 *   Go    -> main.go，必须是 package main + func main()
 *
 * 编辑器如果一开始是空的，用户很容易从 LeetCode 之类的地方复制
 * `class Solution` 过来 —— 文件名对不上，javac 直接报编译错误，
 * 而用户只看到"编译错误"四个字，完全不知道是类名问题。
 * 给一份能直接跑的模板，从源头避免这类低级失败。
 */
export const CODE_TEMPLATES: Record<string, string> = {
  // 模板里用 Scanner 只是因为它是最好懂的写法，不是要求。
  // 想用 BufferedReader / 快读模板 / 任何方式都行，能读标准输入即可。
  java: `// 入口类必须是 Main，且不能有 package 声明
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        // 在这里写你的代码
    }
}
`,
  cpp: `// 入口是 main()
#include <iostream>
using namespace std;

int main() {
    // 在这里写你的代码
    return 0;
}
`,
  go: `// 必须是 package main 且包含 func main()
package main

import "fmt"

func main() {
    // 在这里写你的代码
}
`,
};

/**
 * 各语言的**硬性约束**，显示在编辑器上方。
 *
 * <p>只写"必须怎样"，不写"建议用什么读输入" ——
 * 判题只要求程序从标准输入读数据、向标准输出写答案，
 * 用 Scanner 还是 BufferedReader 还是手写快读，是答题人的自由。
 */
export const LANGUAGE_TIPS: Record<string, string> = {
  java: "入口类必须是 Main，不能有 package 声明。从标准输入读、向标准输出写即可，读写方式不限。",
  cpp: "入口是 main()。从标准输入读、向标准输出写即可，读写方式不限。",
  go: "必须是 package main 且包含 func main()。从标准输入读、向标准输出写即可，读写方式不限。",
};

/** 取某语言的初始模板；未知语言返回空串 */
export function getCodeTemplate(language?: string): string {
  return language ? (CODE_TEMPLATES[language] ?? "") : "";
}
