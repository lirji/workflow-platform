package com.lrj.workflow.server.admin;

import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 运维干预(改状态,需 ADMIN):挂起/恢复/终止流程实例、Flowable 死信作业列出/重试。
 * 只读的实例/轨迹查询走 {@code ProcessQueryService};DLQ(Kafka)重放走 {@code DlqReplayService}。
 */
@Service
public class AdminOpsService {

    private final RuntimeService runtimeService;
    private final ManagementService managementService;
    private final ProcessLinkRepository linkRepo;
    private final WorkflowMetrics metrics;
    private final WorkflowAudit audit;

    public AdminOpsService(RuntimeService runtimeService, ManagementService managementService,
                           ProcessLinkRepository linkRepo, WorkflowMetrics metrics, WorkflowAudit audit) {
        this.runtimeService = runtimeService;
        this.managementService = managementService;
        this.linkRepo = linkRepo;
        this.metrics = metrics;
        this.audit = audit;
    }

    public void suspend(String tenant, String processInstanceId) {
        requireTenantInstance(tenant, processInstanceId);
        runtimeService.suspendProcessInstanceById(processInstanceId);
        metrics.adminOp("suspend");
        audit.adminOp("suspend", processInstanceId, null);
    }

    public void activate(String tenant, String processInstanceId) {
        requireTenantInstance(tenant, processInstanceId);
        runtimeService.activateProcessInstanceById(processInstanceId);
        metrics.adminOp("activate");
        audit.adminOp("activate", processInstanceId, null);
    }

    /** 终止实例并把 link 阶段标记 CANCELLED(不可逆)。 */
    @Transactional
    public void terminate(String tenant, String processInstanceId, String reason) {
        requireTenantInstance(tenant, processInstanceId);
        runtimeService.deleteProcessInstance(processInstanceId, reason == null || reason.isBlank() ? "admin-terminated" : reason);
        linkRepo.markPhase(processInstanceId, ProcessPhase.CANCELLED);
        metrics.adminOp("terminate");
        audit.adminOp("terminate", processInstanceId, reason);
    }

    /** 按租户列出 Flowable 死信作业(执行超限后进死信队列)。 */
    public List<DeadLetterJobView> deadLetterJobs(String tenant, int limit) {
        return managementService.createDeadLetterJobQuery().list().stream()
                .filter(j -> linkRepo.findByTenantAndInstanceId(tenant, j.getProcessInstanceId()).isPresent())
                .limit(limit)
                .map(j -> new DeadLetterJobView(j.getId(), j.getProcessInstanceId(), j.getElementId(),
                        j.getRetries(), j.getExceptionMessage()))
                .toList();
    }

    /** 校验作业所属租户后，把死信作业移回可执行队列并给定重试次数。 */
    public void retryJob(String tenant, String jobId, int retries) {
        var job = managementService.createDeadLetterJobQuery().jobId(jobId).singleResult();
        if (job == null || linkRepo.findByTenantAndInstanceId(tenant, job.getProcessInstanceId()).isEmpty()) {
            throw new org.flowable.common.engine.api.FlowableObjectNotFoundException(
                    "死信作业不存在", org.flowable.job.api.Job.class);
        }
        managementService.moveDeadLetterJobToExecutableJob(jobId, retries);
        metrics.deadLetterRetried();
        audit.jobRetried(jobId, retries);
    }

    private void requireTenantInstance(String tenant, String processInstanceId) {
        if (linkRepo.findByTenantAndInstanceId(tenant, processInstanceId).isEmpty()) {
            throw new org.flowable.common.engine.api.FlowableObjectNotFoundException(
                    "流程实例不存在", org.flowable.engine.runtime.ProcessInstance.class);
        }
    }
}
