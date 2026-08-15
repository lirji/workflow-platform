package com.lrj.workflow.server;

import com.lrj.workflow.server.admin.DefinitionAdminService;
import com.lrj.workflow.server.admin.ProcessDefinitionView;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程定义部署服务(Option B 后端)真实部署验证:部署 BPMN XML → 定义可查。
 * wf_deployment_audit 写入 best-effort(H2 无该表 → 服务内 try/catch 吞掉,不影响部署)。
 */
@SpringBootTest
class DefinitionAdminServiceTest {

    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://lrj.com/wf">
              <process id="demoProc" name="Demo" isExecutable="true">
                <startEvent id="s"/>
                <sequenceFlow id="f1" sourceRef="s" targetRef="t"/>
                <userTask id="t" name="审核" flowable:candidateGroups="REVIEWER"/>
                <sequenceFlow id="f2" sourceRef="t" targetRef="e"/>
                <endEvent id="e"/>
              </process>
            </definitions>
            """;

    @Autowired DefinitionAdminService svc;
    @Autowired RepositoryService repositoryService;

    @AfterEach
    void clean() {
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
    }

    @Test
    void deployThenListAndSuspend() {
        ProcessDefinitionView view = svc.deploy("his", "demoProc", BPMN, "sub-1");
        assertThat(view.key()).isEqualTo("demoProc");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.suspended()).isFalse();

        assertThat(svc.list("his")).extracting(ProcessDefinitionView::key).contains("demoProc");

        svc.suspendDefinition(view.id());
        assertThat(svc.list("his").stream().filter(v -> v.id().equals(view.id())).findFirst().orElseThrow().suspended())
                .isTrue();
    }
}
