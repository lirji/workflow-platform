package com.lrj.workflow.server.web;

import com.lrj.workflow.protocol.event.Actor;
import com.lrj.workflow.server.admin.DefinitionAdminService;
import com.lrj.workflow.server.admin.ProcessDefinitionView;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程定义部署/版本管理 REST(Option B 后端,ADMIN 门控见 SecurityConfig)。
 * 部署 BPMN XML、列出定义、挂起/恢复定义。前端可做「粘贴 XML 部署」或可视化设计器(后续)。
 */
@RestController
@RequestMapping("/api/v1/admin/definitions")
public class AdminDefinitionController {

    /** 部署请求:name(资源名/deployment 名)+ bpmnXml(完整 BPMN 2.0 XML)。 */
    public record DeployProcessRequest(String name, String bpmnXml) {
    }

    private final DefinitionAdminService svc;
    private final WorkflowIdentityResolver identity;

    public AdminDefinitionController(DefinitionAdminService svc, WorkflowIdentityResolver identity) {
        this.svc = svc;
        this.identity = identity;
    }

    @GetMapping
    public List<ProcessDefinitionView> list(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant) {
        return svc.list(identity.tenant(tenant));
    }

    @PostMapping("/deploy")
    public ProcessDefinitionView deploy(
                                        @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                        @RequestBody DeployProcessRequest req) {
        Actor actor = identity.actor(new Actor(null, null, null));
        return svc.deploy(identity.tenant(tenant), req.name(), req.bpmnXml(),
                actor == null ? null : actor.subjectId());
    }

    @PostMapping("/{id}/suspend")
    public ResponseEntity<Void> suspend(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String id) {
        svc.suspendDefinition(identity.tenant(tenant), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String id) {
        svc.activateDefinition(identity.tenant(tenant), id);
        return ResponseEntity.noContent().build();
    }
}
