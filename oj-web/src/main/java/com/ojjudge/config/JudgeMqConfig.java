package com.ojjudge.config;

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
 * 主服务用到的队列声明：判题结果队列（它消费）。
 *
 * <p>交换机在这里也会声明一次 —— RabbitMQ 的声明是幂等的，
 * 两边都写是为了无论谁先启动都能正常收发。
 *
 * <p>判题任务队列不在这里声明：那是 oj-judge-service 的地盘，
 * 让每个服务自己管自己消费的队列，职责清楚。
 */
@Configuration
public class JudgeMqConfig {

    /**
     * 消息改用 JSON 序列化。
     *
     * <p>默认的 {@code SimpleMessageConverter} 走的是 <b>JDK 序列化</b>，有两个问题：
     * <ol>
     *   <li><b>安全</b>：Spring AMQP 对 JDK 反序列化有白名单（防的是利用反序列化
     *       漏洞执行任意代码那一类攻击），我们自己的类不在名单里，
     *       直接抛 {@code SecurityException}。
     *       而"放开白名单"（trust all）等于把门拆了，不能这么干。</li>
     *   <li><b>耦合</b>：JDK 序列化要求收发双方是同一个类、同一个包、同一版本。
     *       判题服务哪天换个语言写就收不到了。</li>
     * </ol>
     * 用 JSON 就没有这两个问题，消息内容还肉眼可读，排查时方便得多。
     *
     * <p>收发两边都配了同一个转换器，否则一边发 JSON 一边按 JDK 解，照样炸。
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
    public Queue judgeResultQueue() {
        return QueueBuilder.durable(JudgeMqConstants.QUEUE_RESULT).build();
    }

    @Bean
    public Binding judgeResultBinding(Queue judgeResultQueue, TopicExchange judgeExchange) {
        return BindingBuilder
                .bind(judgeResultQueue)
                .to(judgeExchange)
                .with(JudgeMqConstants.ROUTING_RESULT);
    }
}
