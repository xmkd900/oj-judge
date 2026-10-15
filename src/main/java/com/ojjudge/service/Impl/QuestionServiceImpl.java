package com.ojjudge.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.ojjudge.common.CommonConstant;
import com.ojjudge.common.ErrorCode;
import com.ojjudge.common.JsonUtils;
import com.ojjudge.common.SortUtils;
import com.ojjudge.dto.JudgeConfig;
import com.ojjudge.dto.JudgeInfo;
import com.ojjudge.dto.QuestionAddDTO;
import com.ojjudge.dto.QuestionAdminQueryDTO;
import com.ojjudge.dto.QuestionEditDTO;
import com.ojjudge.dto.QuestionQueryDTO;
import com.ojjudge.dto.QuestionSubmitAddDTO;
import com.ojjudge.dto.QuestionSubmitQueryDTO;
import com.ojjudge.dto.QuestionUpdateDTO;
import com.ojjudge.dto.SampleCase;
import com.ojjudge.entity.Question;
import com.ojjudge.judge.event.SubmitCreatedEvent;
import com.ojjudge.entity.QuestionSubmit;
import com.ojjudge.exception.BusinessException;
import com.ojjudge.mapper.QuesionMapper;
import com.ojjudge.mapper.QuestionSubmitMapper;
import com.ojjudge.security.LoginUserDetails;
import com.ojjudge.security.SecurityUtils;
import com.ojjudge.service.QuestionService;
import com.ojjudge.service.QuestionTagService;
import com.ojjudge.vo.QuestionSubmitVO;
import com.ojjudge.vo.QuestionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class QuestionServiceImpl extends ServiceImpl<QuesionMapper, Question> implements QuestionService {

    /**
     * 题目默认排序：创建时间倒序，新发的题排最前面
     */
    private static final String DEFAULT_QUESTION_SORT = "createTime";

    private static final String DEFAULT_SUBMIT_SORT = "createTime";

    private final QuestionSubmitMapper questionSubmitMapper;

    private final QuestionTagService questionTagService;

    private final JsonUtils jsonUtils;

    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ------------------------------------------------------------ 查询（脱敏）

    @Override
    public Page<QuestionVO> listQuestionVoByPage(QuestionQueryDTO queryDTO) {
        Page<Question> page = new Page<>(queryDTO.getCurrent(), queryDTO.getPageSize());
        SortUtils.apply(page, queryDTO.getSortField(), queryDTO.getSortOrder(),
                CommonConstant.QUESTION_SORT_FIELDS, DEFAULT_QUESTION_SORT);

        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        wrapper.eq(queryDTO.getId() != null, "id", queryDTO.getId())
                .like(StringUtils.hasText(queryDTO.getTitle()), "title", queryDTO.getTitle());
        applyTagFilter(wrapper, queryDTO.getTags(), queryDTO.getTagMatchMode());

        Page<Question> result = this.page(page, wrapper);
        Page<QuestionVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(toVOList(result.getRecords()));
        return voPage;
    }

    @Override
    public QuestionVO getQuestionVoById(Long id) {
        Question question = this.getById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        return toVO(question, questionTagService.listTagsByQuestionId(id));
    }

    // ---------------------------------------------------------- 查询（完整实体）

    @Override
    public Page<Question> listQuestionByPage(QuestionAdminQueryDTO queryDTO) {
        requireAdmin();

        Page<Question> page = new Page<>(queryDTO.getCurrent(), queryDTO.getPageSize());
        SortUtils.apply(page, queryDTO.getSortField(), queryDTO.getSortOrder(),
                CommonConstant.QUESTION_SORT_FIELDS, DEFAULT_QUESTION_SORT);

        QueryWrapper<Question> wrapper = new QueryWrapper<>();
        wrapper.eq(queryDTO.getId() != null, "id", queryDTO.getId())
                .like(StringUtils.hasText(queryDTO.getTitle()), "title", queryDTO.getTitle())
                .eq(queryDTO.getUserId() != null, "user_id", queryDTO.getUserId());
        applyTagFilter(wrapper, queryDTO.getTags(), queryDTO.getTagMatchMode());

        Page<Question> result = this.page(page, wrapper);
        fillTagList(result.getRecords());
        return result;
    }

    @Override
    public Question getQuestionById(Long id) {
        Question question = this.getById(id);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        requireOwnerOrAdmin(question.getUserId());
        fillTagList(List.of(question));
        return question;
    }

    // ------------------------------------------------------------------ 写操作

    @Override
    public Long addQuestion(QuestionAddDTO addDTO) {
        LoginUserDetails current = SecurityUtils.currentUser();

        Question question = new Question();
        question.setTitle(addDTO.getTitle());
        question.setContent(addDTO.getContent());
        question.setAnswer(addDTO.getAnswer());
        question.setSamples(jsonUtils.toJson(addDTO.getSamples()));
        question.setJudgeCase(jsonUtils.toJson(addDTO.getJudgeCase()));
        question.setJudgeConfig(jsonUtils.toJson(addDTO.getJudgeConfig()));
        // 这些字段由服务端设置 —— DTO 里根本没有，从源头杜绝客户端伪造
        question.setUserId(current.getUserId());
        question.setSubmitNum(0);
        question.setAcceptedNum(0);
        question.setThumbNum(0);
        question.setFavourNum(0);
        question.setCreateTime(LocalDateTime.now());
        question.setUpdateTime(LocalDateTime.now());

        this.save(question);
        // 标签权威数据写关联表；question.tags 只是冗余副本
        questionTagService.replaceTags(question.getId(), addDTO.getTags());
        return question.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean editQuestion(QuestionEditDTO editDTO) {
        Question old = this.getById(editDTO.getId());
        if (old == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        requireOwnerOrAdmin(old.getUserId());
        return applyUpdate(old, editDTO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateQuestion(QuestionUpdateDTO updateDTO) {
        requireAdmin();
        Question old = this.getById(updateDTO.getId());
        if (old == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        return applyUpdate(old, updateDTO);
    }

    @Override
    public boolean deleteQuestion(Long id) {
        Question old = this.getById(id);
        if (old == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }
        requireOwnerOrAdmin(old.getUserId());
        this.removeById(id);
        questionTagService.removeByQuestionId(id);
        return true;
    }

    // ------------------------------------------------------------- 提交与判题

    /**
     * 提交代码。判题是异步的，这里只落库（status=等待中）并派发事件。
     *
     * <p>⚠️ 这里的 {@code @Transactional} <b>不能删</b>。
     * 判题事件用的是 AFTER_COMMIT 语义，没有事务的话监听器根本不会触发，
     * 提交记录就会永远停在"等待中"。
     * 而监听器在提交之后才跑，所以也不会读到未提交的数据 —— 两个要求同时满足。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long doQuestionSubmit(QuestionSubmitAddDTO submitAddDTO) {
        LoginUserDetails current = SecurityUtils.currentUser();

        Question question = this.getById(submitAddDTO.getQuestionId());
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        QuestionSubmit submit = new QuestionSubmit();
        submit.setQuestionId(submitAddDTO.getQuestionId());
        submit.setUserId(current.getUserId());
        submit.setLanguage(submitAddDTO.getLanguage());
        submit.setCode(submitAddDTO.getCode());
        // 判题沙箱尚未接入：这里只落库，状态停在"等待中"
        submit.setStatus(QuestionSubmit.STATUS_WAITING);
        submit.setCreateTime(LocalDateTime.now());
        submit.setUpdateTime(LocalDateTime.now());
        questionSubmitMapper.insert(submit);

        // 提交数 +1；通过数要等判题结束回写，这里不动
        this.lambdaUpdate()
                .eq(Question::getId, question.getId())
                .setSql("submit_num = submit_num + 1")
                .update();

        // 关键：这里不直接调用判题服务。
        // 判题要在事务提交之后跑，否则异步线程读不到刚插入的这条记录。
        eventPublisher.publishEvent(new SubmitCreatedEvent(submit.getId()));
        return submit.getId();
    }

    @Override
    public Page<QuestionSubmitVO> listQuestionSubmitByPage(QuestionSubmitQueryDTO queryDTO) {
        LoginUserDetails current = SecurityUtils.currentUser();
        boolean admin = current.isAdmin();

        Page<QuestionSubmit> page = new Page<>(queryDTO.getCurrent(), queryDTO.getPageSize());
        SortUtils.apply(page, queryDTO.getSortField(), queryDTO.getSortOrder(),
                CommonConstant.QUESTION_SUBMIT_SORT_FIELDS, DEFAULT_SUBMIT_SORT);

        QueryWrapper<QuestionSubmit> wrapper = new QueryWrapper<>();
        wrapper.eq(queryDTO.getQuestionId() != null, "question_id", queryDTO.getQuestionId())
                .eq(queryDTO.getUserId() != null, "user_id", queryDTO.getUserId())
                .eq(queryDTO.getStatus() != null, "status", queryDTO.getStatus())
                .eq(StringUtils.hasText(queryDTO.getLanguage()), "language", queryDTO.getLanguage());

        Page<QuestionSubmit> result = questionSubmitMapper.selectPage(page, wrapper);
        Page<QuestionSubmitVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(item -> toSubmitVO(item, current.getUserId(), admin))
                .toList());
        return voPage;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 把编辑/更新的字段套用到已有题目上。
     * 只覆盖 DTO 中声明过的字段，userId / submitNum 等一律不动。
     */
    /**
     * 部分更新：只覆盖 DTO 里<b>真的传了值</b>的字段。
     *
     * <p>约定（与 {@code UserUpdateMyDTO} 保持一致，全项目统一）：
     * <pre>
     *   字段没传（null）      = 不修改  ← 前端"不想改"就别传
     *   空字符串 / 空数组      = 显式清空
     *   有值                  = 更新
     * </pre>
     *
     * <p>⚠️ 早期版本是无脑 {@code old.setXxx(dto.getXxx())}，后果很严重：
     * 前端少传一个字段，数据库里原本有值的地方就被覆盖成 null —— 而且不报错，
     * 属于最难排查的那种"静默数据丢失"。
     *
     * @throws BusinessException 一个字段都没传时提示，避免发出一次无意义的 UPDATE
     */
    private boolean applyUpdate(Question old, QuestionAddDTO dto) {
        boolean dirty = false;

        if (dto.getTitle() != null) {
            old.setTitle(dto.getTitle());
            dirty = true;
        }
        if (dto.getContent() != null) {
            old.setContent(dto.getContent());
            dirty = true;
        }
        if (dto.getAnswer() != null) {
            old.setAnswer(dto.getAnswer());
            dirty = true;
        }
        if (dto.getSamples() != null) {
            old.setSamples(jsonUtils.toJson(dto.getSamples()));
            dirty = true;
        }
        if (dto.getJudgeCase() != null) {
            old.setJudgeCase(jsonUtils.toJson(dto.getJudgeCase()));
            dirty = true;
        }
        if (dto.getJudgeConfig() != null) {
            old.setJudgeConfig(jsonUtils.toJson(dto.getJudgeConfig()));
            dirty = true;
        }
        // 注意：tags 为 null 表示"不改标签"，传空数组 [] 才是"清空标签"
        boolean tagsChanged = dto.getTags() != null;
        if (tagsChanged) {
            dirty = true;
        }

        if (!dirty) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "没有需要更新的字段");
        }

        old.setUpdateTime(LocalDateTime.now());
        boolean updated = this.updateById(old);

        // 关键：只有真的传了 tags 才动关联表。
        // 否则一次"只改标题"的编辑就会把这道题的所有标签清空。
        if (tagsChanged) {
            questionTagService.replaceTags(old.getId(), dto.getTags());
        }
        return updated;
    }

    /**
     * 把关联表里的标签回填到实体的 tagList 上（非数据库字段）。
     *
     * <p>question 表已经没有 tags 列了，标签只在 question_tag 里。
     * 但 {@code GET /api/question/get} 返回完整实体，编辑页需要标签，
     * 所以这里查出来挂上去，让调用方拿到的是数组而不是要手动 parse 的 JSON 字符串。
     */
    private void fillTagList(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return;
        }
        List<Long> ids = questions.stream().map(Question::getId).toList();
        Map<Long, List<String>> tagsMap = questionTagService.listTagsByQuestionIds(ids);
        for (Question q : questions) {
            q.setTagList(tagsMap.getOrDefault(q.getId(), List.of()));
        }
    }

    /**
     * 批量转换，一次性把标签查回来，避免 N+1
     */
    private List<QuestionVO> toVOList(List<Question> questions) {
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        List<Long> ids = questions.stream().map(Question::getId).toList();
        Map<Long, List<String>> tagsMap = questionTagService.listTagsByQuestionIds(ids);
        return questions.stream()
                .map(q -> toVO(q, tagsMap.getOrDefault(q.getId(), List.of())))
                .toList();
    }

    private QuestionVO toVO(Question question, List<String> tags) {
        QuestionVO vo = new QuestionVO();
        BeanUtils.copyProperties(question, vo);
        // 标签以关联表为准
        vo.setTags(tags == null || tags.isEmpty() ? List.of() : tags);
        vo.setJudgeConfig(jsonUtils.toObject(question.getJudgeConfig(), JudgeConfig.class));
        // 示例是公开的，随 VO 一起返回给做题的人
        vo.setSamples(jsonUtils.toList(question.getSamples(), SampleCase.class));
        return vo;
    }

    /**
     * 标签筛选：先由 QuestionTagService 算出命中的题目 id，再拼进 IN 条件。
     * 这样 tag 列上的索引才能生效（JSON_CONTAINS 实测走不了索引）。
     */
    private void applyTagFilter(QueryWrapper<Question> wrapper, List<String> tags, String matchMode) {
        List<Long> ids = questionTagService.listQuestionIdsByTags(tags, matchMode);
        if (ids == null) {
            return;                 // 没传标签，不加条件
        }
        if (ids.isEmpty()) {
            wrapper.eq("id", -1L);  // 有标签条件但一条都没命中：直接返回空
            return;
        }
        wrapper.in("id", ids);
    }

    private QuestionSubmitVO toSubmitVO(QuestionSubmit submit, Long currentUserId, boolean admin) {
        QuestionSubmitVO vo = new QuestionSubmitVO();
        BeanUtils.copyProperties(submit, vo);
        vo.setJudgeInfo(jsonUtils.toObject(submit.getJudgeInfo(), JudgeInfo.class));
        // 代码只对提交者本人和管理员可见
        if (!admin && !submit.getUserId().equals(currentUserId)) {
            vo.setCode(null);
        }
        return vo;
    }

    /**
     * 必须是作者本人或管理员，否则 40101
     */
    private void requireOwnerOrAdmin(Long ownerId) {
        LoginUserDetails current = SecurityUtils.currentUser();
        if (current.isAdmin() || (ownerId != null && ownerId.equals(current.getUserId()))) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "只能操作自己创建的题目");
    }

    /**
     * 必须是管理员，否则 40101
     */
    private void requireAdmin() {
        if (!SecurityUtils.currentUser().isAdmin()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "需要管理员权限");
        }
    }

}
