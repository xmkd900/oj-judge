package com.ojjudge.common;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 排序工具。
 *
 * <p>做两件关键的事：
 * <ol>
 *   <li><b>白名单校验</b>：sortField 来自客户端，直接拼进 ORDER BY 就是 SQL 注入。
 *       不在白名单里的字段一律忽略，而不是报错（排序失败不该让整个接口挂掉）。</li>
 *   <li><b>camelCase 转下划线</b>：前端传 createTime，数据库列是 create_time。</li>
 * </ol>
 */
public final class SortUtils {

    private SortUtils() {
    }

    /**
     * 把排序条件应用到分页对象上
     *
     * @param page        分页对象
     * @param sortField   排序字段（camelCase），为空或不在白名单则使用默认
     * @param sortOrder   ascend / descend
     * @param allowed     允许的排序字段白名单
     * @param defaultSort 默认排序字段（sortField 无效时用它）
     */
    public static <T> void apply(Page<T> page, String sortField, String sortOrder,
                                 Set<String> allowed, String defaultSort) {
        String field = StringUtils.hasText(sortField) && allowed.contains(sortField)
                ? sortField
                : defaultSort;
        boolean asc = !CommonConstant.SORT_ORDER_DESC.equals(sortOrder);
        String column = camelToUnderline(field);
        page.addOrder(asc ? OrderItem.asc(column) : OrderItem.desc(column));

        // 二级排序：create_time 在 MySQL 里精度只到秒，同一秒创建的记录排序值相同，
        // 只按它排会得到不确定的顺序（刷新一下顺序就变了）。用 id 兜底保证稳定。
        if (!"id".equals(field)) {
            page.addOrder(OrderItem.desc("id"));
        }
    }

    /**
     * createTime -> create_time
     */
    public static String camelToUnderline(String camel) {
        if (camel == null || camel.isEmpty()) {
            return camel;
        }
        StringBuilder sb = new StringBuilder(camel.length() + 4);
        for (char c : camel.toCharArray()) {
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
