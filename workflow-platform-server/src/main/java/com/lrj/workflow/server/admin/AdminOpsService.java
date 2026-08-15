package com.lrj.workflow.server.admin;

import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 运维干预(改状态,需 ADMIN):挂起/恢复/终止流程实例、Flowable 死信作业列出/重试。
 * 只读的实例/轨迹查询走 {@code ProcessQueryService};DLQ(Kafka)重放走 {@code DlqReplayService}。
 */
@Service
public class AdminOpsService {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsService.class);

    private final RuntimeService runtimeService;
    private final ManagementService managementService;
    private final ProcessLinkRepository linkRepo;

    public AdminOpsService(RuntimeService runtimeService, ManagementService managementService,
                           ProcessLinkRepository linkRepo) {
        this.runtimeService = runtimeService;
        this.managementService = managementService;
        this.linkRepo = linkRepo;
    }

    public void suspend(String processInstanceId) {
        runtimeService.suspendProcessInstanceById(processInstanceId);
        log.info("运维挂起实例 pid={}", processInstanceId);
    }

    public void activate(String processInstanceId) {
        runtimeService.activateProcessInstanceById(processInstanceId);
        log.info("运维恢复实例 pid={}", processInstanceId);
    }

    /** 终止实例并把 link 阶段标记 CANCELLED(不可逆)。 */
    public void terminate(String processInstanceId, String reason) {
        runtimeService.deleteProcessInstance(processInstanceId, reason == null || reason.isBlank() ? "admin-terminated" : reason);
        linkRepo.markPhase(processInstanceId, ProcessPhase.CANCELLED);
        log.warn("运维终止实例 pid={} reason={}", processInstanceId, reason);
    }

    /** 列出 Flowable 死信作业(执行超限后进死信队列)。 */
    public List<DeadLetterJobView> deadLetterJobs(int limit) {
        return managementService.createDeadLetterJobQuery().list().stream()
                .limit(limit)
                .map(j -> new DeadLetterJobView(j.getId(), j.getProcessInstanceId(), j.getElementId(),
                        j.getRetries(), j.getExceptionMessage()))
                .toList();
    }

    /** 把死信作业移回可执行队列并给定重试次数。 */
    public void retryJob(String jobId, int retries) {
        managementService.moveDeadLetterJobToExecutableJob(jobId, retries);
        log.info("运维重试死信作业 jobId={} retries={}", jobId, retries);
    }
}
