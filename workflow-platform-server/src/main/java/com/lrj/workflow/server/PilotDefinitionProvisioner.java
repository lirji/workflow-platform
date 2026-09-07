package com.lrj.workflow.server;

import org.flowable.engine.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为试点内置流程幂等准备 BPMN 定义。
 *
 * <p>权益 SKU 流程的 tenant 来自已经完成 source/tenant/HMAC 校验的入站事件，不能再由单一默认
 * tenant 决定。生产环境关闭 {@code workflow.pilot.auto-deploy} 后不会装配本组件，定义仍由 admin
 * 受控部署。
 */
@Component
@ConditionalOnProperty(name = "workflow.pilot.auto-deploy", havingValue = "true", matchIfMissing = true)
public class PilotDefinitionProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PilotDefinitionProvisioner.class);
    private static final String HIS_KEY = "hisRxReview";
    private static final String BENEFIT_KEY = "benefitSkuGoLive";

    private final RepositoryService repositoryService;

    public PilotDefinitionProvisioner(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    /** 启动时保留既有 HIS 与本地默认权益 tenant，避免破坏原有开发入口。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionStartupDefinitions(String benefitTenant) {
        ensureDefinition("his", HIS_KEY);
        ensureDefinition(benefitTenant, BENEFIT_KEY);
    }

    /**
     * 在可信入站命令真正起实例前准备其 tenant 下的权益定义；未知流程绝不自动部署。
     * 独立事务必须先提交，否则随后以 REQUIRES_NEW 发起流程时看不到尚未提交的定义。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provisionForTrustedStart(String tenantId, String definitionKey) {
        if (BENEFIT_KEY.equals(definitionKey)) {
            ensureDefinition(tenantId, definitionKey);
        }
    }

    /** 单 JVM 内串行执行“查后部署”，降低同一 tenant 并发首单产生重复版本的概率。 */
    private synchronized void ensureDefinition(String tenantId, String definitionKey) {
        String tenant = requireText(tenantId, "tenantId");
        long existing = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(definitionKey)
                .processDefinitionTenantId(tenant)
                .count();
        if (existing > 0) {
            log.info("试点 BPMN {} 已就绪(tenant={}),现有版本数={}", definitionKey, tenant, existing);
            return;
        }

        String resource;
        String deploymentName;
        if (HIS_KEY.equals(definitionKey)) {
            resource = "bpmn/his-rx-review-v1.bpmn20.xml";
            deploymentName = "his-rx-review-v1";
        } else if (BENEFIT_KEY.equals(definitionKey)) {
            resource = "bpmn/benefit-sku-golive-v1.bpmn20.xml";
            deploymentName = "benefit-sku-golive-v1";
        } else {
            throw new IllegalArgumentException("不允许自动部署未知流程: " + definitionKey);
        }
        repositoryService.createDeployment()
                .name(deploymentName)
                .addClasspathResource(resource)
                .tenantId(tenant)
                .enableDuplicateFiltering()
                .deploy();
        log.info("试点 BPMN {} 已按可信入站 tenant 部署(tenant={})", definitionKey, tenant);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }
}
