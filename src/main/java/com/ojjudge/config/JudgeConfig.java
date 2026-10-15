package com.ojjudge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 判题线程池。
 *
 * <p>为什么要单独的池而不是用默认的：一次判题要起若干容器、耗时几秒到几十秒，
 * 跟普通请求不是一个量级。单独限流能避免判题把业务线程吃光。
 *
 * <p>队列用有界的（不是默认的 {@code Integer.MAX_VALUE}）——
 * 高峰期宁可让新提交快速失败，也不要无限堆积把内存撑爆。
 */
@Configuration
@EnableAsync
public class JudgeConfig {

    public static final int CORE_POOL_SIZE = 2;

    public static final int MAX_POOL_SIZE = 4;

    public static final int QUEUE_CAPACITY = 100;

    @Bean("judgeExecutor")
    public Executor judgeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("judge-");
        // 队列满了就交给调用方线程执行，等于让提交动作自然变慢（背压），
        // 比无限堆积或直接丢弃都好
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
