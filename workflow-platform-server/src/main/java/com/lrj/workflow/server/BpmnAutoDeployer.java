package com.lrj.workflow.server;

import org.flowable.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(BpmnAutoDeployer.class);

    @Bean
    public ApplicationRunner deployPilotBpmn(
            RepositoryService repositoryService,
            @Value("${workflow.pilot.benefit-tenant:dev-tenant}") String benefitTenant) {
        return args -> {
            repositoryService.createDeployment()
                    .name("his-rx-review-v1")
                    .addClasspathResource("bpmn/his-rx-review-v1.bpmn20.xml")
                    .tenantId("his")
                    .enableDuplicateFiltering()   // 相同资源不重复部署
                    .deploy();
            repositoryService.createDeployment()
                    .name("benefit-sku-golive-v1")
                    .addClasspathResource("bpmn/benefit-sku-golive-v1.bpmn20.xml")
                    .tenantId(benefitTenant)
                    .enableDuplicateFiltering()
                    .deploy();
            long n = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey("hisRxReview").processDefinitionTenantId("his").count();
            log.info("试点 BPMN hisRxReview 已就绪(tenant=his),现有版本数={}", n);
            long benefitDefinitions = repositoryService.createProcessDefinitionQuery()
                    .processDefinitionKey("benefitSkuGoLive")
                    .processDefinitionTenantId(benefitTenant).count();
            log.info("BPMN benefitSkuGoLive 已就绪(tenant={}),现有版本数={}",
                    benefitTenant, benefitDefinitions);
        };
    }
}
