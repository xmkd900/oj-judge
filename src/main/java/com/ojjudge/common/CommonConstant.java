package com.ojjudge.common;

import java.util.Set;

public interface CommonConstant {

    /**
     * 升序。取值沿用前端 Ant Design 的约定（ascend / descend），不是数据库的 asc / desc
     */
    String SORT_ORDER_ASC = "ascend";

    /**
     * 降序。
     */
    String SORT_ORDER_DESC = "descend";

    /**
     * 题目允许排序的字段（前端传 camelCase，这里统一用 camelCase 登记）
     */
    Set<String> QUESTION_SORT_FIELDS =
            Set.of("id", "title", "createTime", "updateTime", "submitNum", "acceptedNum");

    /**
     * 提交记录允许排序的字段
     */
    Set<String> QUESTION_SUBMIT_SORT_FIELDS =
            Set.of("id", "createTime", "status");

    /**
     * 用户列表允许排序的字段
     */
    Set<String> USER_SORT_FIELDS =
            Set.of("id", "createTime", "updateTime", "userName");
}
