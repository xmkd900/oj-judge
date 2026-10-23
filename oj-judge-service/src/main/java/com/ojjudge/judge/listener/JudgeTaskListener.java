package com.ojjudge.judge.listener;

import com.ojjudge.common.mq.JudgeMqConstants;
import com.ojjudge.common.mq.JudgeResultMessage;
import com.ojjudge.common.mq.JudgeTaskMessage;
import com.ojjudge.judge.CodeSandbox;
import com.ojjudge.judge.model.ExecuteRequest;
import com.ojjudge.judge.model.ExecuteResult;
import com.ojjudge.judge.model.JudgeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 判题服务的入口：从消息队列里取任务，跑完把结果发回去。
 *
 * <p><b>这里不碰数据库。</b>要判什么全在 {@link JudgeTaskMessage} 里带过来了
 * （代码、语言、用例输入、期望输出、时限），判完只回一条 {@link JudgeResultMessage}。
 * 落库是 oj-web 的事。
 *
 * <p>正因为无状态，这个服务想开几个实例就开几个 ——
 * RabbitMQ 会把任务均匀地分给它们，这就是"独立扩容"能落地的原因。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JudgeTaskListener {

    private final CodeSandbox codeSandbox;

    private final RabbitTemplate rabbitTemplate;

    /**
     * 收一条判题任务，执行，然后把结论发回去。
     *
     * <p>⚠️ 这个方法<b>不抛异常</b>：沙箱出任何问题都会在这里被接住，
     * 转成一条 SYSTEM_ERROR 的结果发回去。
     *
     * <p>为什么不能往外抛：抛了消息就会重新入队、无限重试，
     * 一条有问题的提交能把整个队列堵死。
     * 而"判不出来"本身就是一个合法的结论 —— 让用户看到「判题服务异常」，
     * 也比让他永远卡在"等待中"强。
     */
    @RabbitListener(queues = JudgeMqConstants.QUEUE_TASK)
    public void onJudgeTask(JudgeTaskMessage task) {
        if (task == null || task.getSubmitId() == null) {
            log.warn("收到空的判题任务，忽略");
            return;
        }

        JudgeResultMessage result = new JudgeResultMessage();
        result.setSubmitId(task.getSubmitId());

        try {
            ExecuteRequest request = new ExecuteRequest();
            request.setLanguage(task.getLanguage());
            request.setCode(task.getCode());
            request.setInputs(task.getInputs());
            request.setTimeLimit(task.getTimeLimit());
            request.setMemoryLimit(task.getMemoryLimit());

            ExecuteResult executeResult = codeSandbox.execute(request);

            // 沙箱自己先判一遍：编译错误、超时、运行错误都在这一步出来。
            // 只有沙箱认为"跑通了"，才需要我们比对输出。
            JudgeStatus status = executeResult.getStatus() == null
                    ? JudgeStatus.ACCEPTED
                    : executeResult.getStatus();
            if (status == JudgeStatus.ACCEPTED) {
                status = compare(task.getExpectedOutputs(), executeResult.getOutputs());
            }

            result.setStatus(status);
            result.setMessage(status.getMessage());
            result.setTime(maxOf(executeResult.getTimes()));
            result.setMemory(executeResult.getMemory());
            // 失败时把编译 / 运行的原始报错透传出去，用户才能自查
            result.setErrorMessage(executeResult.getErrorMessage());
        } catch (Exception e) {
            log.error("判题失败，submitId={}", task.getSubmitId(), e);
            result.setStatus(JudgeStatus.SYSTEM_ERROR);
            result.setMessage(JudgeStatus.SYSTEM_ERROR.getMessage());
            result.setTime(0L);
            result.setErrorMessage(truncate(e.toString(), 500));
        }

        rabbitTemplate.convertAndSend(
                JudgeMqConstants.EXCHANGE, JudgeMqConstants.ROUTING_RESULT, result);
        log.info("判题完成：submitId={} status={}", task.getSubmitId(), result.getStatus());
    }

    /**
     * 拿用户输出比对期望输出。
     *
     * <p>比对前先<b>规范化</b>：统一换行符、去行尾空白、去末尾空行。
     * 不这么做的话 "3" 和 "3 " 会被判成答案错误 —— 那是误判，用户会很困惑。
     */
    private JudgeStatus compare(List<String> expectedOutputs, List<String> actualOutputs) {
        if (actualOutputs == null || expectedOutputs == null
                || actualOutputs.size() != expectedOutputs.size()) {
            return JudgeStatus.RUNTIME_ERROR;
        }
        for (int i = 0; i < expectedOutputs.size(); i++) {
            String expected = normalize(expectedOutputs.get(i));
            String actual = normalize(actualOutputs.get(i));
            if (!expected.equals(actual)) {
                return JudgeStatus.WRONG_ANSWER;
            }
        }
        return JudgeStatus.ACCEPTED;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        // 统一换行符 → 每行去掉行尾空白 → 去掉末尾多出来的空行
        String[] lines = value.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> trimmed = new ArrayList<>();
        for (String line : lines) {
            trimmed.add(line.replaceAll("\\s+$", ""));
        }
        while (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).isEmpty()) {
            trimmed.remove(trimmed.size() - 1);
        }
        return String.join("\n", trimmed);
    }

    private static Long maxOf(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        Long max = 0L;
        for (Long v : values) {
            if (v != null && v > max) {
                max = v;
            }
        }
        return max;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
