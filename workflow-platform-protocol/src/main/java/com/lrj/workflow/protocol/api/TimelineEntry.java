package com.lrj.workflow.protocol.api;

/**
 * 流程轨迹条目(只读),来源 Flowable HistoryService 的历史活动实例。
 *
 * @param activityId    活动 id(BPMN 元素 id,可用于图上高亮)
 * @param activityName  活动名
 * @param activityType  活动类型(userTask/serviceTask/exclusiveGateway/...)
 * @param assignee      办理人(用户任务)
 * @param startEpochMs  开始时间
 * @param endEpochMs    结束时间(null=进行中)
 */
public record TimelineEntry(
        String activityId,
        String activityName,
        String activityType,
        String assignee,
        Long startEpochMs,
        Long endEpochMs
) {
}
