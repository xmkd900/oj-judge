package com.ojjudge.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 判题服务入口。
 *
 * <p>和主服务（oj-web）完全独立：不同进程、不同端口、不共享数据库。
 * 两边只通过 RabbitMQ 说话。
 *
 * <p>这个服务<b>不对外提供任何 HTTP 接口</b>，所以没有引入 web starter。
 * 它的生命周期由消息队列的监听器撑着 —— 有任务就干活，没任务就等着。
 */
@SpringBootApplication
public class JudgeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JudgeServiceApplication.class, args);
    }
}
