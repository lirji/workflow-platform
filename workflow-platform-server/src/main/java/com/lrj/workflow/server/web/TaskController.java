package com.lrj.workflow.server.web;

import com.lrj.workflow.core.task.TaskApplicationService;
import com.lrj.workflow.protocol.api.CompleteReviewRequest;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.protocol.event.Actor;
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

    public TaskController(TaskApplicationService taskApp) {
        this.taskApp = taskApp;
    }

    @GetMapping
    public List<TaskView> list(@RequestHeader("X-Workflow-Tenant") String tenant,
                               @RequestParam(required = false) String definitionKey,
                               @RequestParam(required = false) String businessKey) {
        return taskApp.findTasks(tenant, definitionKey, businessKey);
    }

    @PostMapping("/{taskId}/complete-review")
    public ResponseEntity<Map<String, String>> completeReview(
            @RequestHeader("X-Workflow-Tenant") String tenant,
            @PathVariable String taskId,
            @RequestBody CompleteReviewRequest req) {
        Actor actor = new Actor(req.actorSub(), req.actorUsername(), req.actorDisplayName());
        String actionId = taskApp.completeReview(taskId, tenant, req.decision(), req.opinion(), actor);
        return ResponseEntity.accepted().body(Map.of("actionId", actionId, "status", "PENDING_BUSINESS"));
    }
}
