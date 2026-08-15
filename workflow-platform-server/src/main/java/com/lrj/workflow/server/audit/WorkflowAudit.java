package com.lrj.workflow.server.audit;

import com.lrj.workflow.protocol.event.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 结构化审计日志(独立 logger WORKFLOW_AUDIT,便于采集/分流)。记录人工决定与运维干预等关键动作。
 * 字段为 key=value 便于日志系统解析;不落库(留待后续审计表)。
 */
@Component
public class WorkflowAudit {

    private static final Logger log = LoggerFactory.getLogger("WORKFLOW_AUDIT");

    public void reviewCompleted(String tenant, String taskId, String decision, String actionId, Actor actor) {
        log.info("action=REVIEW_COMPLETE tenant={} taskId={} decision={} actionId={} actorSub={} actorUser={}",
                tenant, taskId, decision, actionId,
                actor == null ? null : actor.subjectId(), actor == null ? null : actor.username());
    }

    public void adminOp(String op, String processInstanceId, String detail) {
        log.warn("action=ADMIN_OP op={} pid={} detail={}", op, processInstanceId, detail);
    }

    public void jobRetried(String jobId, int retries) {
        log.info("action=JOB_RETRY jobId={} retries={}", jobId, retries);
    }

    public void dlqReplayed(long id, String originalTopic) {
        log.info("action=DLQ_REPLAY id={} originalTopic={}", id, originalTopic);
    }
}
