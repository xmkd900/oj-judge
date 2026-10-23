package com.ojjudge.common.mq;

import com.ojjudge.judge.model.JudgeStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 判题结果：judge → web。
 *
 * <p>判题服务算完之后不直接写库（那是 web 的数据），
 * 而是把这个结果发回去，由 web 落库。
 * 这样"提交记录"这张表始终只有 web 一个写方，
 * 不会出现两个服务同时改一行、互相覆盖的情况。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeResultMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 对应的提交记录 id
     */
    private Long submitId;

    /**
     * 判题结论
     */
    private JudgeStatus status;

    /**
     * 给玩家看的结论文字，比如"通过"、"编译错误"
     */
    private String message;

    /**
     * 耗时（毫秒）
     */
    private Long time;

    /**
     * 内存占用（字节）
     */
    private Long memory;

    /**
     * 编译 / 运行时的原始报错。失败时才有，让用户能自查。
     */
    private String errorMessage;
}
