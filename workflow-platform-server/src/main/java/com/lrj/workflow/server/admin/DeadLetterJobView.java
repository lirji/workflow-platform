package com.lrj.workflow.server.admin;

/** 死信作业视图(运维排查/重试)。来源 Flowable ManagementService 死信队列。 */
public record DeadLetterJobView(
        String jobId,
        String processInstanceId,
        String elementId,
        int retries,
        String exceptionMessage
) {
}
