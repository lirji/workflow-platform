package com.lrj.workflow.server.web;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
import com.lrj.workflow.server.admin.AdminOpsService;
import com.lrj.workflow.server.admin.DeadLetterJobView;
import com.lrj.workflow.server.outbox.OutboxRecoveryService;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运维 REST(/api/v1/admin)。鉴权启用时需 ADMIN 权限(SecurityConfig 门控);
 * 实例查询/挂起/恢复/终止、incident 列表、Flowable 死信作业列/重试。
 * (DLQ Kafka 重放见 {@link DlqController};流程定义部署/版本管理为独立 admin 服务的后续。)
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOpsController {

    private final ProcessQueryService query;
    private final AdminOpsService ops;
    private final OutboxRecoveryService outboxRecovery;
    private final WorkflowIdentityResolver identity;

    public AdminOpsController(ProcessQueryService query, AdminOpsService ops,
                              OutboxRecoveryService outboxRecovery, WorkflowIdentityResolver identity) {
        this.query = query;
        this.ops = ops;
        this.outboxRecovery = outboxRecovery;
        this.identity = identity;
    }

    @GetMapping("/instances")
    public List<ProcessInstanceView> instances(
                                               @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                               @RequestParam(required = false) String definitionKey,
                                               @RequestParam(required = false) String phase,
                                               @RequestParam(defaultValue = "100") int limit) {
        return query.search(identity.tenant(tenant), definitionKey, phase, limit);
    }

    @GetMapping("/incidents")
    public List<ProcessInstanceView> incidents(
                                               @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                               @RequestParam(defaultValue = "100") int limit) {
        return query.search(identity.tenant(tenant), null, "INCIDENT", limit);
    }

    @PostMapping("/instances/{id}/suspend")
    public ResponseEntity<Void> suspend(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String id) {
        ops.suspend(identity.tenant(tenant), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/instances/{id}/activate")
    public ResponseEntity<Void> activate(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String id) {
        ops.activate(identity.tenant(tenant), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/instances/{id}/terminate")
    public ResponseEntity<Void> terminate(
                                          @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                          @PathVariable String id,
                                          @RequestParam(required = false) String reason) {
        ops.terminate(identity.tenant(tenant), id, reason);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/jobs/dead-letter")
    public List<DeadLetterJobView> deadLetterJobs(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @RequestParam(defaultValue = "100") int limit) {
        return ops.deadLetterJobs(identity.tenant(tenant), limit);
    }

    @PostMapping("/jobs/{jobId}/retry")
    public ResponseEntity<Void> retryJob(
                                         @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                         @PathVariable String jobId,
                                         @RequestParam(defaultValue = "3") int retries) {
        ops.retryJob(identity.tenant(tenant), jobId, retries);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/outbox/{eventId}/requeue-delivery-unknown")
    public ResponseEntity<Void> requeueDeliveryUnknown(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String eventId,
            @RequestParam String reason) {
        outboxRecovery.requeueDeliveryUnknown(identity.tenant(tenant), eventId, reason);
        return ResponseEntity.noContent().build();
    }
}
