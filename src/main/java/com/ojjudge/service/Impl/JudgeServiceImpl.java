package com.ojjudge.service.Impl;

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

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(null);

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
        executeRequest.setTimeLimit(judgeConfigTimeLimit(question));

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
        // 失败时把编译/运行的原始报错透传出去，用户才能自查。
        // 只给一句"编译错误"等于什么都没说。
        judgeInfo.setErrorMessage(executeResult.getErrorMessage());
        persist(submit, status, judgeInfo);
        return judgeInfo;
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
     * 把题目里存的时间限制（毫秒）交给沙箱；没配就用沙箱默认值。
     */
    private Long judgeConfigTimeLimit(Question question) {
        JudgeConfig judgeConfig = jsonUtils.toObject(question.getJudgeConfig(), JudgeConfig.class);
        if (judgeConfig == null) {
            return null;
        }
        return judgeConfig.getTimeLimit();
    }

    /**
     * 回写状态与判题结果
     */
    private void persist(QuestionSubmit submit, JudgeStatus status, JudgeInfo judgeInfo) {
        QuestionSubmit update = new QuestionSubmit();
        update.setId(submit.getId());
        update.setStatus(status == JudgeStatus.ACCEPTED
                ? QuestionSubmit.STATUS_SUCCEED
                : QuestionSubmit.STATUS_FAILED);
        update.setJudgeInfo(jsonUtils.toJson(judgeInfo));
        update.setUpdateTime(LocalDateTime.now());
        questionSubmitMapper.updateById(update);
    }
}
