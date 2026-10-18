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
 *
 * <h3>关于异常兜底</h3>
 * <p>异步任务的异常<b>不会</b>传播到请求线程，所以必须在这里接住。
 * 但"接住"不等于"只打日志" —— 只打日志的话这条提交会
 * <b>永远停在 status=0（等待中）</b>，用户看到的就是一直转圈；
 * 前端轮询 60 秒后提示"请到提交记录页查看"，而那一页也永远显示"等待中"。
 *
 * <p>所以 catch 里要做两件事：记日志（给排查的人）+ 回写失败状态（给用户）。
 * 状态只能是「判题服务异常」——流程崩了不代表用户的代码有问题，不能判成编译错误。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JudgeTaskExecutor {

    private final JudgeService judgeService;

    /**
     * 收到"提交已创建"事件后异步判题。
     */
    @Async("judgeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(SubmitCreatedEvent event) {
        Long submitId = event.questionSubmitId();
        try {
            judgeService.doJudge(submitId);
        } catch (Exception e) {
            log.error("判题失败，提交记录 id={}", submitId, e);
            markFailed(submitId, e);
        }
    }

    /**
     * 回写失败状态。
     *
     * 这里再包一层 try-catch 不是啰嗦：走到这一步说明判题流程已经出事了
     * （很可能是数据库或基础设施的问题），兜底写库同样可能失败。
     * 让它继续往外抛的话，异常会冒到线程池最外层被彻底吞掉，连个日志都没有。
     */
    private void markFailed(Long submitId, Exception e) {
        try {
            judgeService.markJudgeFailed(submitId, e.toString());
        } catch (Exception inner) {
            log.error("回写失败状态也失败了，提交记录 id={} 将停留在等待中", submitId, inner);
        }
    }
}
