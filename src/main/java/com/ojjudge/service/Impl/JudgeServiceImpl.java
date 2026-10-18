package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.JsonUtils;
import com.ojjudge.dto.JudgeCase;
import com.ojjudge.dto.JudgeConfig;
import com.ojjudge.dto.JudgeInfo;
import com.ojjudge.entity.Question;
import com.ojjudge.entity.QuestionSubmit;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.judge.CodeSandbox;
import com.ojjudge.judge.model.ExecuteRequest;
import com.ojjudge.judge.model.ExecuteResult;
import com.ojjudge.judge.model.JudgeStatus;
import com.ojjudge.mapper.QuesionMapper;
import com.ojjudge.mapper.QuestionSubmitMapper;
import com.ojjudge.service.JudgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 判题服务的默认实现。
 *
 * <h3>职责边界</h3>
 * <ul>
 *   <li>它知道<strong>题目</strong>：取出 judgeCase / judgeConfig</li>
 *   <li>它决定<strong>对错</strong>：拿用户输出比对期望输出</li>
 *   <li>它<b>不</b>关心代码在哪跑：那是 {@link CodeSandbox} 的事</li>
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

    private final CodeSandbox codeSandbox;

    private final JsonUtils jsonUtils;

    /**
     * 对一条提交记录执行判题，并回写状态与 judgeInfo。
     *
     * 判题可能很慢（起若干容器），调用方应当异步执行（见 JudgeTaskExecutor）。
     */
    @Override
    public JudgeInfo doJudge(Long questionSubmitId) {
        QuestionSubmit submit = questionSubmitMapper.selectById(questionSubmitId);
        if (submit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
        }

        Question question = questionMapper.selectById(submit.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        List<JudgeCase> judgeCases = jsonUtils.toList(question.getJudgeCase(), JudgeCase.class);

        JudgeConfig config = jsonUtils.toObject(question.getJudgeConfig(), JudgeConfig.class);

        JudgeInfo judgeInfo = new JudgeInfo();

        if (judgeCases == null || judgeCases.isEmpty()) {
            // 没有用例不能算通过，否则"空题目"会被误判成 AC
            judgeInfo.setMessage(JudgeStatus.SYSTEM_ERROR.getMessage());
            judgeInfo.setTime(0L);
            judgeInfo.setErrorMessage("这道题还没有配置判题用例，无法判题");
            persist(submit, JudgeStatus.SYSTEM_ERROR, judgeInfo);
            return judgeInfo;
        }

        List<String> inputs = new ArrayList<>();
        for (JudgeCase item : judgeCases) {
            inputs.add(item == null || item.getInput() == null ? "" : item.getInput());
        }

        ExecuteRequest executeRequest = new ExecuteRequest();
        executeRequest.setLanguage(submit.getLanguage());
        executeRequest.setCode(submit.getCode());
        executeRequest.setInputs(inputs);
        executeRequest.setTimeLimit(config == null ? null : config.getTimeLimit());
        executeRequest.setMemoryLimit(config == null ? null : config.getMemoryLimit());

        ExecuteResult executeResult;
        try {
            executeResult = codeSandbox.execute(executeRequest);
        } catch (Exception e) {
            // 兜住沙箱的一切异常并落库。
            // 不这么做的话，异常会冒到异步线程最外层被吞掉，
            // 这条提交就永远停在"等待中"，用户干等且日志无线索。
            log.error("调用代码沙箱失败，提交记录 id={}", questionSubmitId, e);
            judgeInfo.setMessage(JudgeStatus.SYSTEM_ERROR.getMessage());
            judgeInfo.setTime(0L);
            // 把原因带上：只显示"判题服务异常"的话，用户和排查的人都无从下手
            judgeInfo.setErrorMessage(truncate(e.toString(), 500));
            persist(submit, JudgeStatus.SYSTEM_ERROR, judgeInfo);
            return judgeInfo;
        }

        JudgeStatus status = executeResult.getStatus() == null
                ? JudgeStatus.ACCEPTED : executeResult.getStatus();
        if (status == JudgeStatus.ACCEPTED) {
            status = compare(judgeCases, executeResult.getOutputs());
        }

        judgeInfo.setMessage(status.getMessage());
        judgeInfo.setTime(maxOf(executeResult.getTimes()));
        judgeInfo.setMemory(executeResult.getMemory());
        // 失败时把编译/运行的原始报错透传出去，用户才能自查。
        // 只给一句"编译错误"等于什么都没说。
        judgeInfo.setErrorMessage(executeResult.getErrorMessage());
        persist(submit, status, judgeInfo);
        return judgeInfo;
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
    private JudgeStatus compare(List<JudgeCase> cases, List<String> outputs) {
        if (outputs == null || outputs.size() != cases.size()) {
            return JudgeStatus.RUNTIME_ERROR;
        }
        for (int i = 0; i < cases.size(); i++) {
            String expected = cases.get(i).getOutput() == null ? "" : cases.get(i).getOutput();
            String actual = outputs.get(i) == null ? "" : outputs.get(i);
            if (!normalize(expected).equals(normalize(actual))) {
                return JudgeStatus.WRONG_ANSWER;
            }
        }
        return JudgeStatus.ACCEPTED;
    }

    /**
     * 输出规范化：统一换行、去行尾空白、去末尾空行
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(stripTrailing(line)).append('\n');
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '\n') {
            end--;
        }
        return sb.substring(0, end);
    }

    /**
     * 截断过长的错误输出。
     *
     * <p>编译报错可能非常长（尤其代码里有一堆语法错误时），
     * 全塞进 judge_info 会把字段撑得很大，前端也没地方展示。
     */
    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...（已截断）";
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    private static Long maxOf(List<Long> times) {
        if (times == null || times.isEmpty()) {
            return null;
        }
        long max = 0;
        for (Long t : times) {
            if (t != null && t > max) {
                max = t;
            }
        }
        return max;
    }

    /**
     * 回写状态与判题结果，并在<b>首次</b>通过时给题目累加通过人数。
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
        } catch (Exception e) {
            // 通过人数只是统计数据，绝不能因为它出错把判题结论给回滚掉
            log.error("累加通过人数失败，题目 id={}", submit.getQuestionId(), e);
        }
    }
}
