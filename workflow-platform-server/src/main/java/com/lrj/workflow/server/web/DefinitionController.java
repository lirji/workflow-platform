package com.lrj.workflow.server.web;

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
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 流程定义只读 REST(console 用):取某 key 最新版本的 BPMN XML(含 DI),供前端 bpmn-js 只读渲染。
 */
@RestController
@RequestMapping("/api/v1/definitions")
public class DefinitionController {

    private final RepositoryService repositoryService;
    private final WorkflowIdentityResolver identity;

    public DefinitionController(RepositoryService repositoryService, WorkflowIdentityResolver identity) {
        this.repositoryService = repositoryService;
        this.identity = identity;
    }

    @GetMapping(value = "/{key}/xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> xml(@RequestHeader("X-Workflow-Tenant") String tenant,
                                      @PathVariable String key) {
        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionTenantId(identity.tenant(tenant))
                .latestVersion().singleResult();
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        try (InputStream is = repositoryService.getProcessModel(def.getId())) {
            return ResponseEntity.ok(StreamUtils.copyToString(is, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("读取流程定义 XML 失败: " + key, e);
        }
    }
}
