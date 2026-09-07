package com.lrj.workflow.server.web;

import com.lrj.workflow.core.process.ProcessQueryService;
import com.lrj.workflow.server.security.WorkflowIdentityResolver;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 流程定义只读 REST(console 用):设计器取某 key 最新版本；轨迹页按 processInstanceId 取实例实际版本。
 */
@RestController
@RequestMapping("/api/v1/definitions")
public class DefinitionController {

    private final RepositoryService repositoryService;
    private final WorkflowIdentityResolver identity;
    private final ProcessQueryService processQuery;

    public DefinitionController(RepositoryService repositoryService, WorkflowIdentityResolver identity,
                                ProcessQueryService processQuery) {
        this.repositoryService = repositoryService;
        this.identity = identity;
        this.processQuery = processQuery;
    }

    @GetMapping(value = "/{key}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> xml(
                                      @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
                                      @PathVariable String key,
                                      @RequestParam(required = false) String processInstanceId) {
        String effectiveTenant = identity.tenant(tenant);
        ProcessDefinition def;
        if (processInstanceId != null && !processInstanceId.isBlank()) {
            String definitionId = processQuery.processDefinitionId(effectiveTenant, processInstanceId, key)
                    .orElse(null);
            if (definitionId == null) {
                return ResponseEntity.notFound().build();
            }
            def = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionId(definitionId).singleResult();
            if (def != null && (!key.equals(def.getKey()) || !effectiveTenant.equals(def.getTenantId()))) {
                def = null;
            }
        } else {
            def = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey(key).processDefinitionTenantId(effectiveTenant)
                    .latestVersion().singleResult();
        }
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream is = repositoryService.getProcessModel(def.getId())) {
            return ResponseEntity.ok(StreamUtils.copyToString(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("读取流程定义 XML 失败: " + key, e);
        }
    }

    /**
     * 返回当前 tenant 是否已部署指定定义。控制台先查本接口，再查待办，即可区分“定义缺失”和
     * “定义存在但当前没有任务”，无需把空列表一律解释成没有业务。
     */
    @GetMapping("/{key}/availability")
    public DefinitionAvailabilityView availability(
            @RequestHeader(value = "X-Workflow-Tenant", required = false) String tenant,
            @PathVariable String key) {
        String effectiveTenant = identity.tenant(tenant);
        boolean deployed = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key)
                .processDefinitionTenantId(effectiveTenant)
                .count() > 0;
        return new DefinitionAvailabilityView(effectiveTenant, key, deployed,
                deployed ? "DEPLOYED" : "DEFINITION_MISSING");
    }

    /** 流程定义可用性只读视图。 */
    public record DefinitionAvailabilityView(
            String tenantId, String definitionKey, boolean deployed, String status) { }
}
