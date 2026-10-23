package com.ojjudge.judge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ojjudge.common.mq.JudgeMqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 判题服务用到的队列声明。
 *
 * <p>只声明<b>自己需要</b>的那部分：判题任务队列（它消费）。
 * 交换机两边都会声明 —— RabbitMQ 的声明是幂等的，
 * 重复声明同样的配置不会报错，这样无论哪个服务先启动都能正常工作。
 *
 * <h3>关于死信</h3>
 * <p>任务队列挂了一个死信交换机。当监听器抛异常、重试次数用尽后，
 * 消息会被丢到 {@code oj.judge.task.dlq} 而不是无限重来。
 *
 * <p>不过要注意：{@code JudgeTaskListener} 现在是<b>刻意不抛异常</b>的
 * （沙箱出错会转成一条「判题服务异常」的结果发回去）。
 * 也就是说正常路径下死信是空的 ——
 * 它防的是将来某天有人在监听器里漏了 try-catch，或者出现未受检的致命错误。
 */
@Configuration
public class JudgeMqConfig {

    /**
     * 消息用 JSON 序列化，不用默认的 JDK 序列化。
     * 原因见 oj-web 里同名的 bean —— 收发两边必须一致。
     */
    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        // 刻意自己 new 一个，而不是注入容器里的那份：
        // 一是这个项目没把 ObjectMapper 注册成 bean（注入会直接启动失败），
        // 二是 MQ 的序列化配置和缓存用的那套互不影响，改一边不会牵连另一边。
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public TopicExchange judgeExchange() {
        return ExchangeBuilder
                .topicExchange(JudgeMqConstants.EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue judgeTaskQueue() {
        return QueueBuilder
                .durable(JudgeMqConstants.QUEUE_TASK)
                .deadLetterExchange(JudgeMqConstants.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(JudgeMqConstants.ROUTING_DEAD_LETTER)
                .build();
    }

    @Bean
    public Binding judgeTaskBinding(Queue judgeTaskQueue, TopicExchange judgeExchange) {
        return BindingBuilder
                .bind(judgeTaskQueue)
                .to(judgeExchange)
                .with(JudgeMqConstants.ROUTING_TASK);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return ExchangeBuilder
                .topicExchange(JudgeMqConstants.DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(JudgeMqConstants.QUEUE_DEAD_LETTER).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(JudgeMqConstants.ROUTING_DEAD_LETTER);
    }
}
