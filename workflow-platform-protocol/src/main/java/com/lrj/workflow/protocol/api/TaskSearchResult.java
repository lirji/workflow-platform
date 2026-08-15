package com.lrj.workflow.protocol.api;

import java.util.List;

/** 待办分页查询结果(候选人过滤 + 分页)。 */
public record TaskSearchResult(
        List<TaskView> items,
        long total,
        int page,
        int size
) {
}
