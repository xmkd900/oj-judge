package com.ojjudge.common.mq;

/**
 * 消息队列的名字与路由键。
 *
 * <p>放在公共模块里，是为了让**发送方和接收方共用同一份定义** ——
 * 各自写一份常量的话，改了一边忘了另一边，消息就悄悄投丢了，
 * 而且这种错误没有任何编译期提示，只能等线上"判题没反应"才发现。
 */
public final class JudgeMqConstants {

    private JudgeMqConstants() {
    }

    /**
     * 判题相关的消息都走这个交换机
     */
    public static final String EXCHANGE = "oj.judge.exchange";

    // ------------------------------------------------------------------ 判题任务（web → judge）

    public static final String QUEUE_TASK = "oj.judge.task";

    public static final String ROUTING_TASK = "judge.task";

    // ------------------------------------------------------------------ 判题结果（judge → web）

    public static final String QUEUE_RESULT = "oj.judge.result";

    public static final String ROUTING_RESULT = "judge.result";

    // ------------------------------------------------------------------ 失败兜底

    /**
     * 死信交换机：判题反复失败的消息最后会落到这里，
     * 不会无限重试把队列堵死。
     */
    public static final String DEAD_LETTER_EXCHANGE = "oj.judge.dlx";

    public static final String QUEUE_DEAD_LETTER = "oj.judge.task.dlq";

    public static final String ROUTING_DEAD_LETTER = "judge.dead";
}
