package com.ojjudge.cache;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 题目列表的"骨架"：总数 + 当前页的 id 顺序。
 *
 * <p>只存 id 而不存整条记录，是为了让它和题目内容解耦 ——
 * 判题回写会改 {@code accepted_num}，但不会改变"有哪些题、共几条"，
 * 所以这份缓存不用跟着判题一起失效（详见 {@link CacheNames#QUESTION_IDS}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionIdPage {

    /**
     * 符合条件的总条数，对应分页的 total。
     */
    private long total;

    /**
     * 当前页的题目 id，顺序与查询时的排序一致。
     */
    private List<Long> ids;
}
