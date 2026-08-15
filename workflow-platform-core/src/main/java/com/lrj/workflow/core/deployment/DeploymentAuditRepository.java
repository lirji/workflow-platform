package com.lrj.workflow.core.deployment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** wf_deployment_audit 写入(流程定义部署/挂起/恢复审计)。 */
@Repository
public class DeploymentAuditRepository {

    private final JdbcTemplate jdbc;

    public DeploymentAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String deploymentId, String defKey, Integer version, String tenant,
                       String bpmnHash, String operatorSub, String action) {
        jdbc.update(
                "INSERT INTO wf_deployment_audit(deployment_id,process_definition_key,process_definition_version,"
                        + "tenant_id,bpmn_hash,operator_sub,action) VALUES (?,?,?,?,?,?,?)",
                deploymentId, defKey, version, tenant, bpmnHash, operatorSub, action);
    }
}
