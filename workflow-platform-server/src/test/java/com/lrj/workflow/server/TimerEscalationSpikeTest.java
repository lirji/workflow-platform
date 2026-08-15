package com.lrj.workflow.server;

import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA/超时能力验证(P2-4.2):审核任务挂非阻塞边界定时器,到期升级到"超时升级处理"且原任务保留。
 * 测试关掉 async executor,用 ManagementService 强制执行定时器 job 模拟到期(不等真实时间)。
 */
@SpringBootTest
class TimerEscalationSpikeTest {

    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired ManagementService managementService;

    @AfterEach
    void clean() {
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
    }

    @Test
    void nonInterruptingBoundaryTimerEscalates() {
        repositoryService.createDeployment()
                .addClasspathResource("bpmn/spike-sla.bpmn20.xml")
                .tenantId("his")
                .deploy();
        ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId("slaReview", "sla-1", "his");

        // 初始:仅"审核"任务
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).list())
                .extracting(Task::getName).containsExactly("审核");

        // 强制执行 SLA 定时器 job(模拟到期)
        Job timer = managementService.createTimerJobQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(timer).as("SLA 定时器 job 应存在").isNotNull();
        managementService.moveTimerToExecutableJob(timer.getId());
        managementService.executeJob(timer.getId());

        // 非阻塞升级后:原"审核" + 新"超时升级处理" 两个任务并存
        assertThat(taskService.createTaskQuery().processInstanceId(pi.getId()).list())
                .extracting(Task::getName)
                .containsExactlyInAnyOrder("审核", "超时升级处理");
    }
}
