package com.ojjudge.cache;

import com.ojjudge.dto.QuestionQueryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 把方法参数序列化成 JSON 字符串当缓存 key，并对语义等价的写法做规范化。
 *
 * <h3>为什么不用默认的 key 生成方式</h3>
 * <p>Spring 默认规则是：单参数方法直接把<b>参数对象本身</b>当 key，
 * 落到 Redis 时用的是它的 {@code toString()}。而 Lombok 的 {@code toString()}
 * 默认不含父类字段 —— {@code current} / {@code pageSize} 会被丢掉，
 * 结果是第 1 页和第 2 页共用同一个 key，翻页永远返回第一页。
 * 详细描述见 {@code CACHE-TUTORIAL.md} 的 4.7 节。
 *
 * <h3>为什么还要多一步"规范化"</h3>
 * <p>光把参数序列化成 JSON 还不够：有些查询写法不同、语义却完全一样，
 * 不规范化就会各占一个 key，白占内存还拉低命中率。实测过的几种：
 * <pre>
 *   tags ["简单","动态规划"]  vs  ["动态规划","简单"]   → 同一结果，却要 2 个 key
 *   tags ["简单","简单"]      vs  ["简单"]              → 同一结果，却要 2 个 key
 *   tags ["  动态规划  "]     vs  ["动态规划"]          → 后端已 trim，同一结果，2 个 key
 * </pre>
 *
 * 所以这里对 tags 做：<b>去空格 → 转小写 → 去重 → 排序</b>。
 * 前两步和 {@code QuestionTagServiceImpl.normalize} 保持一致（后端查询时就是这么处理的），
 * 排序是为了让顺序无关 —— AND 和 OR 都是集合运算，顺序本就不影响结果。
 *
 * <p>空标签数组会直接去掉这个字段：{@code tags=[]} 和"不传 tags"
 * 在后端是等价的（{@code listQuestionIdsByTags} 对空列表返回 null，即不加过滤），
 * 那它们就该是同一个 key。
 *
 * <p>其余字段不特殊处理，直接进 JSON —— 这样<b>新增字段自动进 key</b>，
 * 不需要有人记得来同步（手工列字段清单正是上面那个 bug 的成因）。
 */
@Component("questionQueryKeyGenerator")
@RequiredArgsConstructor
@Slf4j
public class QuestionQueryKeyGenerator implements KeyGenerator {

    private final ObjectMapper objectMapper;

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length == 0) {
            return SimpleKey.EMPTY;
        }
        Object toSerialize = params.length == 1 ? params[0] : List.of(params);
        if (toSerialize == null) {
            return SimpleKey.EMPTY;
        }
        try {
            if (toSerialize instanceof QuestionQueryDTO dto) {
                return canonicalJson(dto);
            }
            return objectMapper.writeValueAsString(toSerialize);
        } catch (Exception e) {
            log.warn("缓存 key 序列化失败，退回 SimpleKey：method={}", method.getName(), e);
            return new SimpleKey(params);
        }
    }

    /**
     * 先把 DTO 转成 JSON 树（这样所有字段都自动包含），再单独规范化 tags。
     */
    private String canonicalJson(QuestionQueryDTO dto) throws Exception {
        ObjectNode tree = objectMapper.valueToTree(dto);
        normalizeTags(tree);
        return objectMapper.writeValueAsString(tree);
    }

    private void normalizeTags(ObjectNode tree) {
        JsonNode tags = tree.get("tags");
        if (tags == null || !tags.isArray()) {
            return;
        }
        // TreeSet 同时完成去重和排序
        TreeSet<String> canonical = new TreeSet<>();
        for (JsonNode node : tags) {
            if (!node.isTextual()) {
                continue;
            }
            String tag = node.asString().trim().toLowerCase(Locale.ROOT);
            if (!tag.isEmpty()) {
                canonical.add(tag);
            }
        }
        if (canonical.isEmpty()) {
            // 空标签 = 没有标签条件，和"不传 tags"合并成同一个 key
            tree.remove("tags");
        } else {
            tree.set("tags", objectMapper.valueToTree(canonical));
        }
    }
}
