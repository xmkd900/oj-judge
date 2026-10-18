package com.ojjudge.judge;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ojjudge.entity.QuestionSubmit;
import com.ojjudge.mapper.QuestionSubmitMapper;
import com.ojjudge.service.JudgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时清理"卡住"的判题记录。
 *
 * <h3>为什么需要它</h3>
 * <p>判题是在后台线程里跑的。如果进程在判题途中被杀掉 / 重启 / 崩溃，
 * 那些还没写完状态的记录会<b>永远停在 status=0（等待中）</b> ——
 * 没有任何代码会再来管它们，用户在提交记录页看到的就是一排永久转圈。
 *
 * <p>这是和 {@link JudgeTaskExecutor} 里那个 catch <b>同源</b>的问题：
 * 一个是"判题过程中抛异常"，一个是"判题还没来得及跑完进程就没了"，
 * 结果都一样 —— 记录永远停在等待中。
 *
 * <h3>为什么可以放心地全部标记失败</h3>
 * <p>判断依据很朴素：<b>应用刚启动，此刻不可能有任何判题在跑</b>。
 * 所以凡是 status 还停在 0（等待中）/ 1（判题中）的，一定是上次进程留下的残骸。
 *
 * <p>标记成「判题服务异常」而不是「失败」的空结论，是为了如实说明原因：
 * 不是用户代码错了，是我们的判题过程中断了，重新提交即可。
 *
 * <p>⚠️ 多实例部署时别开这个 —— 另一个实例可能正在判这些记录。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingSubmitRecovery {

    private final QuestionSubmitMapper questionSubmitMapper;

    private final JudgeService judgeService;

    @Value("${judge.recovery.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!enabled) {
            return;
        }
        List<QuestionSubmit> pending = questionSubmitMapper.selectList(
                new QueryWrapper<QuestionSubmit>()
                        .in("status", QuestionSubmit.STATUS_WAITING, QuestionSubmit.STATUS_RUNNING));
        if (pending.isEmpty()) {
            return;
        }
        log.warn("发现 {} 条未完成的判题记录（上次进程退出时中断），将标记为判题服务异常",
                pending.size());
        for (QuestionSubmit submit : pending) {
            try {
                judgeService.markJudgeFailed(submit.getId(), "判题过程中进程退出，请重新提交");
            } catch (Exception e) {
                // 一条失败不影响其它条
                log.error("恢复提交记录失败，id={}", submit.getId(), e);
            }
        }
    }
}
