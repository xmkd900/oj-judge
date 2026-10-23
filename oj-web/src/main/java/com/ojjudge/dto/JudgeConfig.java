package com.ojjudge.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 题目配置
 *
 * <p>实现 {@link Serializable}：缓存值现在用 JSON，其实并不强制要求。
 * 保留着是留个后手 —— 万一将来改回 JDK 序列化，
 * 对象图里少一个 Serializable 整个 {@code QuestionVO} 就存不进 Redis。
 */
@Data
public class JudgeConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 时间限制，单位<b>毫秒</b>
     */
    private Long timeLimit;

    /**
     * 内存限制，单位<b>字节</b>。
     *
     * <p>⚠️ 这里曾经标注为 KB —— 按 KB 理解的话前端默认的 268435456 会变成 256GB。
     * 前端默认值 268435456 就是 256MB，单位是字节。
     */
    private Long memoryLimit;

    /**
     * 堆栈限制，单位字节。判题逻辑暂未使用，仅存储展示
     */
    private Long stackLimit;
}
