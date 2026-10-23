package com.ojjudge.common.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 判题任务：web → judge。
 *
 * <h3>为什么消息里要带全</h3>
 * <p>拆成微服务后，judge 那边<b>不连业务库</b>。
 * 它需要的一切（代码、语言、输入输出用例、时间内存限制）都得在这条消息里带过去。
 *
 * <p>这么做换来两个好处：
 * <ul>
 *   <li>判题服务是<b>无状态</b>的 —— 可以随意起多个实例，挂了也不丢东西</li>
 *   <li>服务之间只靠消息耦合，谁也碰不到谁的数据库
 *       （共库的话，题目表结构一改，判题服务也得跟着改，那就不叫解耦了）</li>
 * </ul>
 *
 * <p>代价是消息里多了用例数据，但判题用例本来就不大。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JudgeTaskMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 提交记录 id。判题结果要按它回写。
     */
    private Long submitId;

    /**
     * 编程语言，取值见 LanguageEnum
     */
    private String language;

    /**
     * 用户提交的代码
     */
    private String code;

    /**
     * 判题用例的**输入**部分，顺序和 expectedOutputs 一一对应。
     */
    private List<String> inputs;

    /**
     * 每个用例的**期望输出**，顺序和 inputs 一一对应。
     *
     * <p>为什么连期望输出一起带过来：判题服务应当能<b>独立完成判定</b>，
     * 执行完直接给出"通过 / 答案错误 / 编译错误"这样的结论。
     * 否则它就只是个"执行器"，真正的判定还得在 web 侧再做一遍 ——
     * 那样判定逻辑就散在两个服务里，题目用例格式一改两边都得动。
     *
     * <p>至于答案泄露：RabbitMQ 是内网通信，且消息不落业务库，风险可接受。
     */
    private List<String> expectedOutputs;

    /**
     * 时间限制（毫秒），可为 null 表示不限制
     */
    private Long timeLimit;

    /**
     * 内存限制（字节），可为 null 表示不限制
     */
    private Long memoryLimit;
}
