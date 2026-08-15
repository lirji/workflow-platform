package com.lrj.workflow.server.web;

import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.protocol.event.Actor;
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
 * 任务运行时 REST(:8300)。租户暂由 X-Workflow-Tenant 头/参数承载;Phase 3 authz 落地后主体/租户从 JWT 派生。
 * 办理返回 202 + PENDING_BUSINESS —— 人工决定已受理,业务落地经 Kafka 最终一致,不伪装已落地。
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskApplicationService taskApp;
    private final WorkflowIdentityResolver identity;

    public TaskController(TaskApplicationService taskApp, WorkflowIdentityResolver identity) {
        this.taskApp = taskApp;
        this.identity = identity;
    }

    @GetMapping
    public List<TaskView> list(@RequestHeader("X-Workflow-Tenant") String tenant,
                               @RequestParam(required = false) String definitionKey,
                               @RequestParam(required = false) String businessKey) {
        return taskApp.findTasks(identity.tenant(tenant), definitionKey, businessKey);
    }

    /** 待办中心用:候选组过滤 + 分页。candidateGroup 可重复(?candidateGroup=PHARMACIST&candidateGroup=...)。 */
    @GetMapping("/search")
    public com.lrj.workflow.protocol.api.TaskSearchResult search(
            @RequestHeader("X-Workflow-Tenant") String tenant,
            @RequestParam(required = false) String definitionKey,
            @RequestParam(required = false) String businessKey,
            @RequestParam(required = false) List<String> candidateGroup,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskApp.searchTasks(identity.tenant(tenant), definitionKey, businessKey, candidateGroup, page, size);
    }

    @PostMapping("/{taskId}/complete-review")
    public ResponseEntity<Map<String, String>> completeReview(
            @RequestHeader("X-Workflow-Tenant") String tenant,
            @PathVariable String taskId,
            @RequestBody CompleteReviewRequest req) {
        // 鉴权启用时 actor 由 JWT 派生覆盖请求体(可信办理人真相);未启用则用请求体(shadow 由消费方传可信身份)。
        Actor actor = identity.actor(new Actor(req.actorSub(), req.actorUsername(), req.actorDisplayName()));
        String actionId = taskApp.completeReview(taskId, identity.tenant(tenant), req.decision(), req.opinion(), actor);
        return ResponseEntity.accepted().body(Map.of("actionId", actionId, "status", "PENDING_BUSINESS"));
    }
}
