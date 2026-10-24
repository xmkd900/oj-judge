package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ojjudge.cache.CacheNames;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.JsonUtils;
import com.ojjudge.dto.JudgeCase;
import com.ojjudge.dto.JudgeConfig;
import com.ojjudge.dto.JudgeInfo;
import com.ojjudge.entity.Question;
import com.ojjudge.entity.QuestionSubmit;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.common.mq.JudgeMqConstants;
import com.ojjudge.common.mq.JudgeResultMessage;
import com.ojjudge.common.mq.JudgeTaskMessage;
import com.ojjudge.judge.model.JudgeStatus;
import com.ojjudge.mapper.QuesionMapper;
import com.ojjudge.mapper.QuestionSubmitMapper;
import com.ojjudge.service.JudgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * 判题服务的默认实现。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>它知道<strong>题目</strong>：取出 judgeCase / judgeConfig</li>
 *   <li>它<b>不</b>决定对错，也<b>不</b>跑代码：判定已经整个搬到 oj-judge-service 了</li>
 *   <li>它负责<strong>出题</strong>（把用例打包成消息）和<strong>收卷</strong>（把结果写回库）</li>
 * </ul>
 *
 * <h3>判定逻辑</h3>
 * <ol>
 *   <li>先编译。编译不过直接判「编译错误」，不再跑用例 —— 省得每个用例都炸一遍。</li>
 *   <li>逐用例运行。任一用例超时 → 「超时」；非零退出 → 「运行错误」。</li>
 *   <li>比对前先<b>规范化</b>输出：统一换行符、去行尾空白、去末尾空行。
 *       不这么做的话 "3" 和 "3 " 会被判成 WA —— 那是误判。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeServiceImpl implements JudgeService {

    private final QuestionSubmitMapper questionSubmitMapper;

    private final QuesionMapper questionMapper;

    private final RabbitTemplate rabbitTemplate;

    private final RabbitAdmin rabbitAdmin;

    private final JsonUtils jsonUtils;

    private final CacheManager cacheManager;

    /**
     * 待判题队列的堆积上限，超过就拒绝新提交（过载保护）。
     * 0 或负数 = 不启用这个保护。
     */
    @Value("${judge.max-pending-tasks:500}")
    private int maxPendingTasks;

    /**
     * 把判题任务发出去。
     *
     * <p>这里<b>不做判题</b>，只做三件事：查出提交内容、取出题目的用例和限制、
     * 打包成 {@link JudgeTaskMessage} 丢给 RabbitMQ。
     * 真正的执行和判定在 oj-judge-service 那边（另一个进程）。
     *
     * <p>为什么不在本地判：见 MICROSERVICE.md。一句话 ——
     * 判题要跑不可信代码、吃 CPU、流量独立波动，值得单独部署和扩容。
     */
    @Override
    public void dispatchJudge(Long questionSubmitId) {
        QuestionSubmit submit = questionSubmitMapper.selectById(questionSubmitId);
        if (submit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
        }

        Question question = questionMapper.selectById(submit.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        List<JudgeCase> judgeCases = jsonUtils.toList(question.getJudgeCase(), JudgeCase.class);
        if (judgeCases == null || judgeCases.isEmpty()) {
            // 没有用例不能算通过，否则"空题目"会被误判成 AC
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setMessage(JudgeStatus.SYSTEM_ERROR.getMessage());
            judgeInfo.setTime(0L);
            judgeInfo.setErrorMessage("这道题还没有配置判题用例，无法判题");
            persist(submit, JudgeStatus.SYSTEM_ERROR, judgeInfo);
            return;
        }

        // 队列已经堆得很长就别再往里塞了 —— 见 max-pending-tasks 的注释。
        // 检查放在查完题目之后：能走到这一步说明题目和用例都没问题，
        // 此时拒绝才是真的因为"忙"，而不是因为参数不合法。
        if (isQueueOverloaded()) {
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setMessage("系统繁忙，请稍后再试");
            judgeInfo.setTime(0L);
            judgeInfo.setErrorMessage("当前待判题的提交过多（超过 " + maxPendingTasks
                    + " 条），请稍等一会儿再提交");
            persist(submit, JudgeStatus.SYSTEM_ERROR, judgeInfo);
            log.warn("判题队列已满，拒绝本次提交：submitId={}", submit.getId());
            return;
        }

        JudgeConfig config = jsonUtils.toObject(question.getJudgeConfig(), JudgeConfig.class);

        List<String> inputs = new ArrayList<>();
        List<String> expectedOutputs = new ArrayList<>();
        for (JudgeCase item : judgeCases) {
            inputs.add(item == null || item.getInput() == null ? "" : item.getInput());
            expectedOutputs.add(item == null || item.getOutput() == null ? "" : item.getOutput());
        }

        JudgeTaskMessage task = new JudgeTaskMessage();
        task.setSubmitId(submit.getId());
        task.setLanguage(submit.getLanguage());
        task.setCode(submit.getCode());
        task.setInputs(inputs);
        task.setExpectedOutputs(expectedOutputs);
        task.setTimeLimit(config == null ? null : config.getTimeLimit());
        task.setMemoryLimit(config == null ? null : config.getMemoryLimit());

        rabbitTemplate.convertAndSend(
                JudgeMqConstants.EXCHANGE, JudgeMqConstants.ROUTING_TASK, task);
        log.info("判题任务已发出：submitId={} language={} 用例数={}",
                submit.getId(), submit.getLanguage(), inputs.size());
    }

    /**
     * 待判题的队列是否已经堆得过长。
     *
     * <p>⚠️ 这里刻意"查不到就放行"：限流是为了保护系统，
     * 不是一道必须拦住的门禁。RabbitAdmin 查不到队列信息时
     * （比如权限不足、连接抖动），宁可放行让判题正常跑，
     * 也不能把正常用户的提交挡在外面 —— 那是把保护变成了故障。
     */
    private boolean isQueueOverloaded() {
        if (maxPendingTasks <= 0) {
            return false;   // 0 或负数 = 不启用这个保护
        }
        try {
            Properties props = rabbitAdmin.getQueueProperties(JudgeMqConstants.QUEUE_TASK);
            Object count = props == null ? null : props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count instanceof Number number && number.intValue() >= maxPendingTasks;
        } catch (Exception e) {
            log.warn("查询判题队列长度失败，本次不限制流", e);
            return false;
        }
    }

    /**
     * 收回判题结果并写库。
     *
     * <p>判题服务算完不直接写数据库 —— 它把结果发回来，由我们落库。
     * 这样「提交记录」这张表始终只有本服务一个写方，
     * 不会出现两个服务同时改一行互相覆盖的情况。
     *
     * <p>方法签名里没有手动 ack：Spring AMQP 默认在方法正常返回后自动确认。
     * 只有抛异常才会让消息重新入队重试（重试次数由 RabbitMQ 侧配置）。
     */
    @RabbitListener(queues = JudgeMqConstants.QUEUE_RESULT)
    public void onJudgeResult(JudgeResultMessage message) {
        if (message == null || message.getSubmitId() == null) {
            log.warn("收到空的判题结果，忽略");
            return;
        }

        QuestionSubmit submit = questionSubmitMapper.selectById(message.getSubmitId());
        if (submit == null) {
            // 提交记录可能已被删除，记一笔日志就行，不能抛异常 ——
            // 抛了会让消息无限重试，把队列堵死
            log.warn("判题结果对应的提交不存在，丢弃：submitId={}", message.getSubmitId());
            return;
        }

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(message.getMessage());
        judgeInfo.setTime(message.getTime());
        judgeInfo.setMemory(message.getMemory());
        judgeInfo.setErrorMessage(message.getErrorMessage());

        persist(submit, message.getStatus(), judgeInfo);
        log.info("判题结果已回写：submitId={} status={}", message.getSubmitId(), message.getStatus());
    }

    @Override
    public void markJudgeFailed(Long questionSubmitId, String reason) {
        if (questionSubmitId == null) {
            return;
        }
        QuestionSubmit submit = questionSubmitMapper.selectById(questionSubmitId);
        if (submit == null) {
            return;
        }
        // 已经出结果就别覆盖。
        // 崩溃可能发生在"结果已写回、方法还没返回"这个窗口里 ——
        // 那时真结论已经落库了，再盖一层"判题服务异常"会把真结果弄丢。
        Integer status = submit.getStatus();
        if (status != null && status >= QuestionSubmit.STATUS_SUCCEED) {
            log.warn("提交记录 id={} 已有结论（status={}），不覆盖为失败", questionSubmitId, status);
            return;
        }

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeStatus.SYSTEM_ERROR.getMessage());
        judgeInfo.setTime(0L);
        // 把原因带上，否则用户只看到"判题服务异常"，排查的人也无从下手
        judgeInfo.setErrorMessage(truncate(reason, 500));
        persist(submit, JudgeStatus.SYSTEM_ERROR, judgeInfo);
        log.warn("已把提交记录 id={} 标记为判题服务异常", questionSubmitId);
    }

    /**
     * 截断过长的报错文本，免得把一大坨堆栈塞进数据库再糊到用户脸上。
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 逐用例比对输出。
     *
     * <p>比对前统一做规范化：
     * <ul>
     *   <li>换行符统一为 {@code \n}（Windows 的 {@code \r\n} 会误判）</li>
     *   <li>每一行的<b>行尾空白</b>去掉（用户输出多个空格很常见）</li>
     *   <li>末尾的多余空行去掉</li>
     * </ul>
     */
    private void persist(QuestionSubmit submit, JudgeStatus status, JudgeInfo judgeInfo) {
        boolean accepted = status == JudgeStatus.ACCEPTED;

        QuestionSubmit update = new QuestionSubmit();
        update.setId(submit.getId());
        update.setStatus(accepted
                ? QuestionSubmit.STATUS_SUCCEED
                : QuestionSubmit.STATUS_FAILED);
        update.setJudgeInfo(jsonUtils.toJson(judgeInfo));
        update.setUpdateTime(LocalDateTime.now());
        questionSubmitMapper.updateById(update);

        if (accepted) {
            increaseAcceptedNum(submit);
        }
    }

    /**
     * 给题目累加通过人数。
     *
     * <p><b>口径是「通过人数」而不是「通过次数」</b> —— 同一个用户对同一道题
     * 反复提交通过只算一次。这样 {@code accepted_num / submit_num}
     * 才是常见的「通过率」表达，而且天然幂等：判题被重跑一次也不会重复加。
     *
     * <p>判断方法：查该用户在这道题上，除了本次以外还有没有 status=2 的提交。
     * 因为 {@link QuestionSubmit#STATUS_SUCCEED} 只会被 Accepted 写入
     * （其余结论一律写 STATUS_FAILED），所以用 status 就能判断，不用去解析
     * judge_info 里的 message。
     */
    private void increaseAcceptedNum(QuestionSubmit submit) {
        try {
            Long previous = questionSubmitMapper.selectCount(new QueryWrapper<QuestionSubmit>()
                    .eq("question_id", submit.getQuestionId())
                    .eq("user_id", submit.getUserId())
                    .eq("status", QuestionSubmit.STATUS_SUCCEED)
                    .ne("id", submit.getId()));
            if (previous != null && previous > 0) {
                return;                 // 之前已经通过过，不重复计数
            }
            questionMapper.update(null, new LambdaUpdateWrapper<Question>()
                    .eq(Question::getId, submit.getQuestionId())
                    .setSql("accepted_num = accepted_num + 1"));
            // accepted_num 变了，题目详情缓存里的"通过率"就过期了。
            // 这里之所以不用 @CacheEvict 注解：本方法是 private，注解不会被代理拦截 ——
            // 加在 doJudge 上又拿不到具体是哪道题（它返回的是 JudgeInfo）。
            evictQuestionCache(submit.getQuestionId());
        } catch (Exception e) {
            // 通过人数只是统计数据，绝不能因为它出错把判题结论给回滚掉
            log.error("累加通过人数失败，题目 id={}", submit.getQuestionId(), e);
        }
    }

    /**
     * 清除某道题的详情缓存。
     *
     * <p>手写调用<b>不走</b> {@code CacheConfig} 里的 {@code CacheErrorHandler}
     * （那个只拦注解式的操作），所以这里必须自己兜住 ——
     * Redis 抖动时不能让判题线程抛异常出去。
     */
    private void evictQuestionCache(Long questionId) {
        try {
            Cache cache = cacheManager.getCache(CacheNames.QUESTION);
            if (cache != null) {
                cache.evict(questionId);
            }
        } catch (Exception e) {
            // 清不掉最多让通过率显示旧值，等 TTL 到期自动纠正
            log.warn("清除题目缓存失败，题目 id={}，等 TTL 到期后自动纠正", questionId, e);
        }
    }
}
