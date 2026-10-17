package com.ojjudge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.ojjudge.dto.QuestionAdminQueryDTO;
import com.ojjudge.dto.QuestionAddDTO;
import com.ojjudge.dto.QuestionEditDTO;
import com.ojjudge.dto.QuestionQueryDTO;
import com.ojjudge.dto.QuestionSubmitAddDTO;
import com.ojjudge.dto.QuestionSubmitQueryDTO;
import com.ojjudge.dto.QuestionUpdateDTO;
import com.ojjudge.entity.Question;
import com.ojjudge.entity.QuestionSubmit;
import com.ojjudge.vo.QuestionSubmitVO;
import com.ojjudge.vo.QuestionVO;

public interface QuestionService extends IService<Question> {

    /**
     * 分页查询题目（脱敏），公开
     */
    Page<QuestionVO> listQuestionVoByPage(QuestionQueryDTO queryDTO);

    /**
     * 查询题目详情（脱敏），公开
     */
    QuestionVO getQuestionVoById(Long id);

    /**
     * 分页查询题目（含答案），管理员
     */
    Page<Question> listQuestionByPage(QuestionAdminQueryDTO queryDTO);

    /**
     * 查询题目完整信息（含答案、测试用例），作者本人或管理员
     */
    Question getQuestionById(Long id);

    /**
     * 创建题目，返回新题目 id
     */
    Long addQuestion(QuestionAddDTO addDTO);

    /**
     * 编辑题目，作者本人或管理员
     */
    boolean editQuestion(QuestionEditDTO editDTO);

    /**
     * 更新题目，管理员通道（不校验归属）
     */
    boolean updateQuestion(QuestionUpdateDTO updateDTO);

    /**
     * 删除题目，作者本人或管理员
     */
    boolean deleteQuestion(Long id);

    /**
     * 提交代码。判题是异步的，这里只写库（status=0），返回提交记录 id
     */
    Long doQuestionSubmit(QuestionSubmitAddDTO submitAddDTO);

    /**
     * 分页查询提交记录。
     *
     * <p><b>普通用户只会看到自己的提交</b>，前端传的 userId 会被忽略；
     * 管理员可以看全部，也可以按 userId 筛选。
     */
    Page<QuestionSubmitVO> listQuestionSubmitByPage(QuestionSubmitQueryDTO queryDTO);

    /**
     * 按 id 查单条提交记录。本人或管理员，否则 40101。
     *
     * <p>给提交后的轮询用：一次只查一条，不用拉一整页再本地 find。
     */
    QuestionSubmitVO getQuestionSubmitById(Long id);
}
