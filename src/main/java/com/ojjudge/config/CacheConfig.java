package com.ojjudge.config;

import com.ojjudge.cache.CacheNames;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.type.TypeFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ojjudge.cache.QuestionIdPage;
import com.ojjudge.vo.QuestionVO;
import com.ojjudge.vo.TagVO;

/**
 * 缓存配置。
 *
 * <h3>为什么用 Spring Cache 抽象，而不是自己写 RedisTemplate</h3>
 * <p>缓存逻辑写在业务方法上（{@code @Cacheable} / {@code @CacheEvict}），
 * 读代码的人一眼就知道"这里会走缓存、那里会清缓存"，不用追进工具类里看。
 * 将来要换本地缓存（Caffeine）或者做多级缓存，只换这个 Bean，业务代码一行不用改。
 *
 * <h3>三个必须做的决定</h3>
 * <ol>
 *   <li><b>Redis 挂了要能降级。</b>Spring 默认的 {@code SimpleCacheErrorHandler} 会把异常往外抛，
 *       Redis 一断整个站就 500 —— 等于给系统加了个单点故障。这里换成"记日志 + 放行"：
 *       读不到就正常查库（见 {@link #errorHandler()}）。</li>
 *   <li><b>值用 JSON，并且是"带类型"的 JSON。</b>理由见下面「序列化」一节。</li>
 *   <li><b>每个缓存都有 TTL。</b>失效点万一漏了一处（缓存最典型的 bug），
 *       到期也会自动纠正，不会永久返回错误数据。</li>
 * </ol>
 *
 * <h3>序列化：为什么是 "带类型" 的 JSON</h3>
 * <p>踩过一轮坑，过程记下来免得以后重走：
 * <ul>
 *   <li><b>第一版用 JDK 序列化</b>。能用，但 Redis 里是一坨二进制 —— 排障时看不见数据。
 *       实测体积也只有 1.3~2.5 倍（964 字节 vs 388 字节），绝对值很小，
 *       所以换掉它的理由是<b>可读性</b>和<b>类结构宽容度</b>，不是体积。</li>
 *   <li><b>不能直接用 {@code RedisSerializer.json()}</b>：它默认不写类型信息，
 *       反序列化只能拿到 {@code LinkedHashMap}，转不回 {@code QuestionVO}。</li>
 *   <li><b>也不能开 default typing</b>：一旦写类型信息，{@code List.of()} 这类不可变集合
 *       的运行期类型（{@code ImmutableCollections$ListN}）会被写进 JSON，
 *       反序列化时因为"没有可用的构造器"直接失败。</li>
 * </ul>
 * <p>正确做法是 {@link JacksonJsonRedisSerializer}：<b>类型在构造时就定死</b>
 * （传 {@code JavaType}），值里就不需要任何类型信息了 —— 上面两个问题都不存在。
 *
 * <p>⚠️ <b>由此引出一条约定</b>：改了 {@code @Cacheable} 方法的返回类型，
 * 必须同步改这里声明的 {@code JavaType}，否则会静默写错类型。
 * 所以类型声明和缓存名放在同一个类里，方便对照。
 *
 * <p>顺带：key 统一加 {@code oj:v1:} 前缀。将来类结构大改、不想兼容旧缓存时，
 * 把 v1 改成 v2 就整体切走，不用去 Redis 里手动清。
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig implements CachingConfigurer {

    /**
     * 标签缓存的兜底过期时间（分钟）。
     */
    @Value("${cache.ttl.tags-minutes:30}")
    private long tagsTtlMinutes;

    /**
     * 题目详情缓存的兜底过期时间（分钟）。
     */
    @Value("${cache.ttl.question-minutes:10}")
    private long questionTtlMinutes;

    /**
     * 列表骨架缓存的兜底过期时间（分钟）。
     *
     * <p>可以给得比题目详情长：它只在题目增删改时才失效，
     * 判题回写不影响它，所以能稳稳地命中。
     */
    @Value("${cache.ttl.question-ids-minutes:30}")
    private long questionIdsTtlMinutes;

    /**
     * key 前缀。带版本号：将来类结构不兼容旧缓存时，把 v1 改成 v2 就整体切走。
     */
    private static final String KEY_PREFIX = "oj:v1:";

    /**
     * 每个缓存存什么类型，集中声明在这里 —— 和 {@link CacheNames} 一一对应，
     * 改了 {@code @Cacheable} 方法的返回类型，必须同步改这里。
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          ObjectMapper objectMapper) {
        TypeFactory types = objectMapper.getTypeFactory();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        // 标签：整份 List<TagVO>
        perCache.put(CacheNames.TAGS, cacheConfig(
                Duration.ofMinutes(tagsTtlMinutes),
                jsonSerializer(objectMapper, types.constructCollectionType(List.class, TagVO.class))));
        // 题目详情：单个 QuestionVO
        perCache.put(CacheNames.QUESTION, cacheConfig(
                Duration.ofMinutes(questionTtlMinutes),
                jsonSerializer(objectMapper, types.constructType(QuestionVO.class))));
        // 列表骨架：总数 + id 列表。只有没筛选的查询会进来（见 QuestionCacheService）
        perCache.put(CacheNames.QUESTION_IDS, cacheConfig(
                Duration.ofMinutes(questionIdsTtlMinutes),
                jsonSerializer(objectMapper, types.constructType(QuestionIdPage.class))));

        return RedisCacheManager.builder(connectionFactory)
                // 没在上面登记的缓存名会用这份默认配置（同样带 TTL，不会永生）。
                // 它没有类型信息，只能退回 JDK 序列化 —— 真用到了，就把它登记进来。
                .cacheDefaults(cacheConfig(Duration.ofMinutes(questionTtlMinutes),
                        RedisSerializer.java()))
                .withInitialCacheConfigurations(perCache)
                .build();
    }

    private RedisCacheConfiguration cacheConfig(Duration ttl, RedisSerializer<Object> valueSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // 不缓存 null。否则"这道题不存在"会被记住，
                // 等题目真建出来了可能还返回空
                .disableCachingNullValues()
                // key 用 String：redis-cli KEYS 'oj:v1:*' 能直接看到可读的 key
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer))
                .computePrefixWith(name -> KEY_PREFIX + name + "::");
    }

    /**
     * 造一个"类型定死"的 JSON 序列化器。
     *
     * <p>强转是必要的：缓存框架只认 {@code RedisSerializer<Object>}，
     * 而这里需要的是具体类型 —— 强转后只有这一个缓存会用它，不会串台。
     */
    @SuppressWarnings("unchecked")
    private static RedisSerializer<Object> jsonSerializer(ObjectMapper mapper, JavaType type) {
        return (RedisSerializer<Object>) (RedisSerializer<?>)
                new JacksonJsonRedisSerializer<>(mapper, type);
    }

    /**
     * Redis 出问题时不让请求挂掉。
     *
     * <p>四个回调都只是记日志、不往外抛：
     * <ul>
     *   <li>读失败 → 缓存返回 null，拦截器会继续走原方法（正常查库）</li>
     *   <li>写 / 清失败 → 跳过这一步，TTL 兜底会纠正</li>
     * </ul>
     *
     * <p>这是有意的选择：<b>缓存是加速手段，不能成为可用性依赖</b>。
     * 宁可慢一点，也不要因为 Redis 抖动让整个站不可用。
     *
     * <p>注意它只对<b>注解式</b>的缓存操作生效。手写的
     * {@code cacheManager.getCache(...).evict(...)} 不走这里，得自己 try-catch
     * （{@code JudgeServiceImpl#evictQuestionCache} 就是这么做的）。
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("缓存读取失败，本次直接查库：cache={} key={}", cache.getName(), key, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("缓存写入失败，不影响业务（检查 CacheConfig 里声明的 JavaType"
                                + " 和 @Cacheable 方法的返回类型是否一致）：cache={} key={}",
                        cache.getName(), key, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("缓存清除失败，TTL 到期后会自动纠正：cache={} key={}", cache.getName(), key, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("缓存清空失败，TTL 到期后会自动纠正：cache={}", cache.getName(), e);
            }
        };
    }
}
