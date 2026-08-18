package com.lrj.workflow.server.web;

import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.server.audit.WorkflowAudit;
import com.lrj.workflow.server.metrics.WorkflowMetrics;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务运行时 REST(:8300)。dev 从 X-Workflow-Tenant 取租户；安全启用并配置 tenant claim 时从 JWT 派生。
 * 办理返回 202 + PENDING_BUSINESS —— 人工决定已受理,业务落地经 Kafka 最终一致,不伪装已落地。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskApplicationService taskApp;
    private final WorkflowIdentityResolver identity;
    private final WorkflowMetrics metrics;
    private final WorkflowAudit audit;

    public TaskController(TaskApplicationService taskApp, WorkflowIdentityResolver identity,
                          WorkflowMetrics metrics, WorkflowAudit audit) {
        this.taskApp = taskApp;
        this.identity = identity;
        this.metrics = metrics;
        this.audit = audit;
    }

    @GetMapping
    public List<TaskView> list(@RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                               @RequestParam(required = false) String definitionKey,
                               @RequestParam(required = false) String businessKey) {
        return taskApp.findTasks(identity.tenant(tenant), definitionKey, businessKey, identity.taskAccess());
    }

    /** 待办中心用:候选组过滤 + 分页。candidateGroup 可重复(?candidateGroup=PHARMACIST&candidateGroup=...)。 */
    @GetMapping("/search")
    public com.lrj.workflow.protocol.api.TaskSearchResult search(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @RequestParam(required = false) String definitionKey,
            @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) List<String> candidateGroup,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskApp.searchTasks(identity.tenant(tenant), definitionKey, businessKey, candidateGroup, page, size,
                identity.taskAccess());
    }

    @PostMapping("/{taskId}/complete-review")
    public ResponseEntity<Map<String, String>> completeReview(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String taskId,
            @RequestBody CompleteReviewRequest req) {
        // 鉴权启用时 actor 由 JWT 派生覆盖请求体(可信办理人真相);未启用则用请求体(shadow 由消费方传可信身份)。
        String effTenant = identity.tenant(tenant);
        Actor actor = identity.actor(new Actor(req.actorSub(), req.actorUsername(), req.actorDisplayName()));
        String actionId = taskApp.completeReview(taskId, effTenant, req.decision(), req.opinion(), actor,
                identity.taskAccess());
        metrics.reviewCompleted(effTenant, req.decision());
        audit.reviewCompleted(effTenant, taskId, req.decision(), actionId, actor);
        return ResponseEntity.accepted().body(Map.of("actionId", actionId, "status", "PENDING_BUSINESS"));
    }

    /** 认领:设办理人。POST /api/v1/tasks/{taskId}/claim?userId= */
    @PostMapping("/{taskId}/claim")
    public ResponseEntity<Void> claim(@RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                      @PathVariable String taskId, @RequestParam String userId) {
        String t = identity.tenant(tenant);
        taskApp.claimTask(t, taskId, userId, identity.taskAccess());
        audit.taskOp(t, "claim", taskId, userId);
        return ResponseEntity.noContent().build();
    }

    /** 转办:改办理人。POST /api/v1/tasks/{taskId}/reassign?assignee= */
    @PostMapping("/{taskId}/reassign")
    public ResponseEntity<Void> reassign(@RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                         @PathVariable String taskId, @RequestParam String assignee) {
        String t = identity.tenant(tenant);
        taskApp.reassignTask(t, taskId, assignee, identity.taskAccess());
        audit.taskOp(t, "reassign", taskId, assignee);
        return ResponseEntity.noContent().build();
    }

    /** 委派。POST /api/v1/tasks/{taskId}/delegate?userId= */
    @PostMapping("/{taskId}/delegate")
    public ResponseEntity<Void> delegate(@RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                         @PathVariable String taskId, @RequestParam String userId) {
        String t = identity.tenant(tenant);
        taskApp.delegateTask(t, taskId, userId, identity.taskAccess());
        audit.taskOp(t, "delegate", taskId, userId);
        return ResponseEntity.noContent().build();
    }

    /** 撤回认领(回候选池)。POST /api/v1/tasks/{taskId}/unclaim */
    @PostMapping("/{taskId}/unclaim")
    public ResponseEntity<Void> unclaim(@RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                        @PathVariable String taskId) {
        String t = identity.tenant(tenant);
        taskApp.unclaimTask(t, taskId, identity.taskAccess());
        audit.taskOp(t, "unclaim", taskId, null);
        return ResponseEntity.noContent().build();
    }
}
