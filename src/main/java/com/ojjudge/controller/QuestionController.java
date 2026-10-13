package com.ojjudge.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ojjudge.common.BaseResponse;
import com.ojjudge.dto.DeleteRequest;
import com.ojjudge.dto.QuestionAddDTO;
import com.ojjudge.dto.QuestionAdminQueryDTO;
import com.ojjudge.dto.QuestionEditDTO;
import com.ojjudge.dto.QuestionQueryDTO;
import com.ojjudge.dto.QuestionSubmitAddDTO;
import com.ojjudge.dto.QuestionSubmitQueryDTO;
import com.ojjudge.dto.QuestionUpdateDTO;
import com.ojjudge.entity.Question;
import com.ojjudge.service.QuestionService;
import com.ojjudge.service.QuestionTagService;
import com.ojjudge.vo.QuestionSubmitVO;
import com.ojjudge.vo.QuestionVO;
import com.ojjudge.vo.TagVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目接口。
 *
 * <p>权限校验全部下沉到 Service，Controller 只负责转发与参数绑定：
 * <ul>
 *   <li>公开：list/page/vo、get/vo</li>
 *   <li>需登录：add、question_submit/do、question_submit/list/page
 *       （由 SecurityConfig 的 {@code anyRequest().authenticated()} 保证）</li>
 *   <li>作者本人或管理员：get、edit、delete</li>
 *   <li>仅管理员：list/page、update</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/question")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    private final QuestionTagService questionTagService;

    /**
     * 分页查询题目（脱敏）。公开。
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<QuestionVO>> listQuestionVoByPage(
            @RequestBody(required = false) QuestionQueryDTO queryDTO) {
        return BaseResponse.success(
                questionService.listQuestionVoByPage(queryDTO == null ? new QuestionQueryDTO() : queryDTO));
    }

    /**
     * 查询题目详情（脱敏）。公开。
     */
    @GetMapping("/get/vo")
    public BaseResponse<QuestionVO> getQuestionVoById(@RequestParam("id") Long id) {
        return BaseResponse.success(questionService.getQuestionVoById(id));
    }

    /**
     * 分页查询题目（含答案）。管理员。
     */
    @PostMapping("/list/page")
    public BaseResponse<Page<Question>> listQuestionByPage(
            @RequestBody(required = false) QuestionAdminQueryDTO queryDTO) {
        return BaseResponse.success(questionService.listQuestionByPage(
                queryDTO == null ? new QuestionAdminQueryDTO() : queryDTO));
    }

    /**
     * 查询题目完整信息（含答案、测试用例）。作者本人或管理员。
     */
    @GetMapping("/get")
    public BaseResponse<Question> getQuestionById(@RequestParam("id") Long id) {
        return BaseResponse.success(questionService.getQuestionById(id));
    }

    /**
     * 创建题目。需登录。
     *
     * <p>这里显式指定 OnCreate 分组，所以 title / content <b>必填</b>。
     */
    @PostMapping("/add")
    public BaseResponse<Long> addQuestion(
            @RequestBody @Validated(QuestionAddDTO.OnCreate.class) QuestionAddDTO addDTO) {
        return BaseResponse.success(questionService.addQuestion(addDTO));
    }

    /**
     * 编辑题目。作者本人或管理员。
     *
     * <p>用默认分组：所有字段<b>可选</b>，没传的就是"不想改"，
     * 服务端不会拿 null 去覆盖原值。只有 id 是必填的。
     */
    @PostMapping("/edit")
    public BaseResponse<Boolean> editQuestion(@RequestBody @Validated QuestionEditDTO editDTO) {
        return BaseResponse.success(questionService.editQuestion(editDTO));
    }

    /**
     * 更新题目（管理员通道，不校验归属）。管理员。分组规则同 edit。
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateQuestion(@RequestBody @Validated QuestionUpdateDTO updateDTO) {
        return BaseResponse.success(questionService.updateQuestion(updateDTO));
    }

    /**
     * 删除题目。作者本人或管理员。
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteQuestion(@RequestBody @Valid DeleteRequest deleteRequest) {
        return BaseResponse.success(questionService.deleteQuestion(deleteRequest.getId()));
    }

    /**
     * 提交代码。返回提交记录 id。
     *
     * <p>判题是异步的：当前沙箱未接入，状态会停在"等待中"，需要轮询查结果。
     */
    @PostMapping("/question_submit/do")
    public BaseResponse<Long> doQuestionSubmit(@RequestBody @Valid QuestionSubmitAddDTO submitAddDTO) {
        return BaseResponse.success(questionService.doQuestionSubmit(submitAddDTO));
    }

    /**
     * 全部已使用的标签及使用次数。公开，供前端下拉选择。
     *
     * <p>有了它前端就不用让用户手打标签，从源头避免 "Java"/"java" 这种同义词混乱。
     */
    @GetMapping("/tags")
    public BaseResponse<List<TagVO>> listAllTags() {
        return BaseResponse.success(questionTagService.listAllTags());
    }

    /**
     * 分页查询提交记录。非本人且非管理员时 code 字段为 null。
     */
    @PostMapping("/question_submit/list/page")
    public BaseResponse<Page<QuestionSubmitVO>> listQuestionSubmitByPage(
            @RequestBody(required = false) QuestionSubmitQueryDTO queryDTO) {
        return BaseResponse.success(questionService.listQuestionSubmitByPage(
                queryDTO == null ? new QuestionSubmitQueryDTO() : queryDTO));
    }
}
