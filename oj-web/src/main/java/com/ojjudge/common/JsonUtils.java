package com.ojjudge.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * JSON 转换工具。
 *
 * <p>题目表里 tags / judgeCase / judgeConfig、提交记录表里 judgeInfo 都是 JSON 字符串，
 * 存取都要转换。解析失败一律返回 null 而不抛异常 —— 一条脏数据不该让整个列表接口挂掉。
 *
 * <p>注意：Spring Boot 4 用的是 Jackson 3，包名是 tools.jackson 而不是 com.fasterxml。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtils {

    private final ObjectMapper objectMapper;

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return null;
        }
    }

    public <T> T toObject(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("JSON 反序列化失败，返回 null：{}", json);
            return null;
        }
    }

    /**
     * 反序列化成 {@code List<元素类型>}。
     *
     * <p>⚠️ 不要写成 {@code new TypeReference<List<T>>(){}} —— 那是假泛型：
     * 泛型变量 T 在运行时被擦除，Jackson 只会解析出 {@code List<LinkedHashMap>}，
     * 元素再赋值给具体类型时会 ClassCastException。
     * 之前只用它反序列化 List&lt;String&gt; 才侥幸没暴露（JSON 字符串天然就是 String）。
     * 必须用 TypeFactory 显式构造带元素类型的集合类型。
     */
    public <T> List<T> toList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            log.warn("JSON 数组反序列化失败，返回 null：{}", json);
            return null;
        }
    }
}
