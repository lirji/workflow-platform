package com.lrj.workflow.server.web;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
import com.lrj.workflow.protocol.api.TimelineEntry;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程实例只读 REST(console 用):按 businessKey 查实例阶段、查历史轨迹。用于办理后诚实展示"已落地/待落地/INCIDENT"。
 */
@RestController
@RequestMapping("/api/v1/process-instances")
public class ProcessController {

    private final ProcessQueryService query;
    private final WorkflowIdentityResolver identity;

    public ProcessController(ProcessQueryService query, WorkflowIdentityResolver identity) {
        this.query = query;
        this.identity = identity;
    }

    @GetMapping
    public List<ProcessInstanceView> byBusinessKey(@RequestHeader("X-Workflow-Tenant") String tenant,
                                                   @RequestParam String definitionKey,
                                                   @RequestParam String businessKey) {
        return query.findByBusinessKey(identity.tenant(tenant), definitionKey, businessKey);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProcessInstanceView> get(@PathVariable String id) {
        return query.getInstance(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@PathVariable String id) {
        return query.timeline(id);
    }
}
