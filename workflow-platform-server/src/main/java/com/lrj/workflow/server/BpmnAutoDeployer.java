package com.lrj.workflow.server;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * 试点期在 server 启动时部署 HIS 审方与 benefit SKU 上线 BPMN。Flowable 按资源校验和去重，重启不产生冗余版本。
 * 生产环境流程定义部署应由 admin 管控;此为试点便利,{@code workflow.pilot.auto-deploy=false} 可关。
 */
@Configuration
@ConditionalOnProperty(name = "workflow.pilot.auto-deploy", havingValue = "true", matchIfMissing = true)
public class BpmnAutoDeployer {
    @Bean
    public ApplicationRunner deployPilotBpmn(
            PilotDefinitionProvisioner provisioner,
            @Value("${workflow.pilot.benefit-tenant:dev-tenant}") String benefitTenant) {
        return args -> provisioner.provisionStartupDefinitions(benefitTenant);
    }
}
