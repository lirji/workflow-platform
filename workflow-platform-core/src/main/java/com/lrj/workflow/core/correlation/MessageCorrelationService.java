package com.lrj.workflow.core.correlation;

import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.protocol.event.WorkflowActionAppliedV1;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务落地 ACK 关联回流程:校验 实例 → pendingActionId → message 订阅,再 messageEventReceived 推进。
 * 早到(订阅未就绪)返回 WAITING_SUBSCRIPTION 让 inbox 置 WAITING_CORRELATION 重试,不丢弃。
 */
@Service
public class MessageCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(MessageCorrelationService.class);
    private static final String MSG = "hisRxReviewApplied";

    public enum Outcome { CORRELATED, WAITING_SUBSCRIPTION, INSTANCE_GONE, ACTION_MISMATCH }

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final ProcessLinkRepository linkRepo;

    public MessageCorrelationService(RuntimeService runtimeService, TaskService taskService,
                                     ProcessLinkRepository linkRepo) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.linkRepo = linkRepo;
    }

    @Transactional
    public Outcome correlate(WorkflowActionAppliedV1 applied) {
        String pid = applied.processInstanceId();
        boolean running = runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() > 0;
        if (!running) {
            log.info("关联:实例已不在运行(可能已处理) pid={}", pid);
            return Outcome.INSTANCE_GONE;
        }
        Object pendingActionId = runtimeService.getVariable(pid, "actionId");
        if (pendingActionId == null || !pendingActionId.equals(applied.actionId())) {
            log.warn("关联:actionId 不匹配 pid={} pending={} applied={}", pid, pendingActionId, applied.actionId());
            return Outcome.ACTION_MISMATCH;
        }
        Execution exec = runtimeService.createExecutionQuery()
                .processInstanceId(pid).messageEventSubscriptionName(MSG).singleResult();
        if (exec == null) {
            log.info("关联:message 订阅未就绪(回执早到) pid={}", pid);
            return Outcome.WAITING_SUBSCRIPTION;
        }
        runtimeService.setVariable(pid, "appliedStatus", applied.status().name());
        runtimeService.messageEventReceived(MSG, exec.getId());
        transition(pid);
        log.info("关联成功并推进 pid={} status={}", pid, applied.status());
        return Outcome.CORRELATED;
    }

    private void transition(String instanceId) {
        ProcessLink link = linkRepo.findByInstanceId(instanceId).orElse(null);
        if (link == null) {
            return;
        }
        ProcessPhase next;
        boolean ended = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).count() == 0;
        if (ended) {
            next = ProcessPhase.COMPLETED;
        } else {
            boolean hasTask = taskService.createTaskQuery().processInstanceId(instanceId).count() > 0;
            next = hasTask ? ProcessPhase.INCIDENT : ProcessPhase.WAITING_BUSINESS;
        }
        if (next != link.phase()) {
            linkRepo.updatePhase(instanceId, next, link.version());
        }
    }
}
