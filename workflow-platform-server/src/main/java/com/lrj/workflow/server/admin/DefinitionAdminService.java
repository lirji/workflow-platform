package com.lrj.workflow.server.admin;

import com.lrj.workflow.core.deployment.DeploymentAuditRepository;
import com.lrj.workflow.server.audit.WorkflowAudit;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 流程定义部署/版本管理(Option B 后端,ADMIN)。部署 BPMN XML → Flowable deployment + wf_deployment_audit;
 * 列出/挂起/恢复定义。审计为 best-effort(不阻塞部署)。
 */
@Service
public class DefinitionAdminService {

    private static final Logger log = LoggerFactory.getLogger(DefinitionAdminService.class);

    private final RepositoryService repositoryService;
    private final DeploymentAuditRepository auditRepo;
    private final WorkflowAudit workflowAudit;

    public DefinitionAdminService(RepositoryService repositoryService, DeploymentAuditRepository auditRepo,
                                  WorkflowAudit workflowAudit) {
        this.repositoryService = repositoryService;
        this.auditRepo = auditRepo;
        this.workflowAudit = workflowAudit;
    }

    /** 部署 BPMN XML。名称用于资源名与 deployment 名。返回新部署的定义视图。 */
    public ProcessDefinitionView deploy(String tenant, String name, String bpmnXml, String operatorSub) {
        String safeName = name == null || name.isBlank() ? "process" : name;
        Deployment dep;
        try {
            dep = repositoryService.createDeployment()
                    .name(safeName)
                    .tenantId(tenant)
                    .addString(safeName + ".bpmn20.xml", bpmnXml)
                    .deploy();
        } catch (Exception e) {
            throw new IllegalArgumentException("BPMN 部署失败: " + e.getMessage());
        }
        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .deploymentId(dep.getId()).singleResult();
        try {
            auditRepo.record(dep.getId(), def == null ? null : def.getKey(), def == null ? null : def.getVersion(),
                    tenant, sha256(bpmnXml), operatorSub, "DEPLOY");
        } catch (Exception e) {
            log.warn("部署审计写入失败(best-effort) deployment={}: {}", dep.getId(), e.getMessage());
        }
        workflowAudit.adminOp("deploy-definition", def == null ? dep.getId() : def.getId(),
                "key=" + (def == null ? "?" : def.getKey()) + " v=" + (def == null ? "?" : def.getVersion()));
        return toView(def);
    }

    public List<ProcessDefinitionView> list(String tenant) {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionTenantId(tenant)
                .orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionVersion().desc()
                .list().stream().map(this::toView).toList();
    }

    public void suspendDefinition(String id) {
        repositoryService.suspendProcessDefinitionById(id);
        workflowAudit.adminOp("suspend-definition", id, null);
    }

    public void activateDefinition(String id) {
        repositoryService.activateProcessDefinitionById(id);
        workflowAudit.adminOp("activate-definition", id, null);
    }

    private ProcessDefinitionView toView(ProcessDefinition d) {
        if (d == null) {
            return null;
        }
        return new ProcessDefinitionView(d.getId(), d.getKey(), d.getName(), d.getVersion(),
                d.getTenantId(), d.isSuspended(), d.getDeploymentId());
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
