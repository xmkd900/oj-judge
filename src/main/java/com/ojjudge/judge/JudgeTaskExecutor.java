package com.ojjudge.judge;

import com.ojjudge.judge.event.SubmitCreatedEvent;
import com.ojjudge.service.JudgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 提交落库后触发异步判题。
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)} —— 事务提交后才执行，
 * 这样判题线程一定读得到刚插入的提交记录。
 *
 * <p>为什么判题必须异步：一次判题要起若干容器，耗时几秒到几十秒，
 * 绝不能卡在提交接口上。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JudgeTaskExecutor {

    private final JudgeService judgeService;

    /**
     * 收到"提交已创建"事件后异步判题。
     *
     * 方法内做了兜底 try-catch：异步任务的异常不会传播到请求线程，
 * 不接住的话这次提交就永远停在"等待中"，且日志里找不到原因。
     */
    @Async("judgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(SubmitCreatedEvent event) {
        Long submitId = event.questionSubmitId();
        try {
            judgeService.doJudge(submitId);
        } catch (Exception e) {
            log.error("判题失败，提交记录 id={} 状态将停留在等待中", submitId, e);
        }
    }
}
