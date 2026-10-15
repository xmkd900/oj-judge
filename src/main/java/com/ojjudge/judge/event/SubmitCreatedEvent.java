package com.ojjudge.judge.event;

/**
 * 一条提交记录刚落库。
 *
 * <p>为什么要绕一圈事件而不是直接在提交方法里异步调判题：
 * 提交记录的 INSERT 还在事务里、尚未提交，此时异步线程去查会<b>查不到</b>。
 * 用 AFTER_COMMIT 语义的事件，能保证监听器拿到时数据已落定。
 */
public record SubmitCreatedEvent(Long questionSubmitId) {
}
