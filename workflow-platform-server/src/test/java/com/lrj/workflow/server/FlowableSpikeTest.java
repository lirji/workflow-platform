package com.lrj.workflow.server;

import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0 兼容硬门禁:证明 Flowable {@code ${flowable.version}} 在 Spring Boot 3.3.5 / JDK21 上
 * 可启动引擎并完成部署/发起/办理/历史/并发/多版本/动态加签。直接注入四大 Service(遵守"不抽象引擎端口"ADR)。
 */
@SpringBootTest
class FlowableSpikeTest {

    @Autowired RepositoryService repositoryService;
    @Autowired RuntimeService runtimeService;
    @Autowired TaskService taskService;
    @Autowired HistoryService historyService;

    private static final String TENANT = "his";

    /** 每个测试后清空所有部署(级联删运行时/历史),保证 @SpringBootTest 复用同一 H2 库时各测试互不干扰。 */
    @AfterEach
    void cleanup() {
        repositoryService.createDeploymentQuery().list()
                .forEach(d -> repositoryService.deleteDeployment(d.getId(), true));
    }

    private void deployHello() {
        repositoryService.createDeployment()
                .name("spike-hello")
                .addClasspathResource("bpmn/spike-hello.bpmn20.xml")
                .tenantId(TENANT)
                .deploy();
    }

    /** 门禁 1:引擎启动 + tenant 部署 + 发起(带 businessKey)+ UserTask 办理 + 历史落地。 */
    @Test
    void engineBootsAndRunsUserTaskToHistory() {
        deployHello();

        long defs = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("spikeHello")
                .processDefinitionTenantId(TENANT)
                .count();
        assertThat(defs).isEqualTo(1);

        ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId(
                "spikeHello", "enc-1001", TENANT);
        assertThat(pi).isNotNull();
        assertThat(pi.getTenantId()).isEqualTo(TENANT);
        assertThat(pi.getBusinessKey()).isEqualTo("enc-1001");

        Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("review");
        // 候选组来自 BPMN
        List<org.flowable.identitylink.api.IdentityLink> links =
                taskService.getIdentityLinksForTask(task.getId());
        assertThat(links).anyMatch(l -> "reviewers".equals(l.getGroupId()));

        taskService.complete(task.getId());

        // 运行时清空
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(pi.getId()).count())
                .isZero();
        // 历史结束且保留 businessKey
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId()).singleResult();
        assertThat(hpi).isNotNull();
        assertThat(hpi.getEndTime()).isNotNull();
        assertThat(hpi.getBusinessKey()).isEqualTo("enc-1001");
    }

    /** 门禁 2:并发发起(不同 businessKey)各自独立成实例、各生成一个待办。 */
    @Test
    void concurrentStartsCreateIndependentInstances() throws Exception {
        deployHello();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<>();
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            final String bk = "batch-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId(
                            "spikeHello", bk, TENANT);
                    ids.add(pi.getId());
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures.get()).isZero();
        assertThat(ids).hasSize(n);
        long tasks = taskService.createTaskQuery()
                .taskCandidateGroup("reviewers")
                .processDefinitionKey("spikeHello")
                .count();
        assertThat(tasks).isGreaterThanOrEqualTo(n);
    }

    /** 门禁 3:同 key 重复部署产生新版本(流程定义版本演进)。 */
    @Test
    void redeploySameKeyCreatesNewVersion() {
        deployHello();
        deployHello();
        List<org.flowable.engine.repository.ProcessDefinition> defs =
                repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey("spikeHello")
                        .processDefinitionTenantId(TENANT)
                        .orderByProcessDefinitionVersion().asc()
                        .list();
        assertThat(defs).hasSize(2);
        assertThat(defs.get(0).getVersion()).isEqualTo(1);
        assertThat(defs.get(1).getVersion()).isEqualTo(2);
    }

    /** 门禁 4(待验证能力):并行会签运行时动态加签 API 是否可用(FINAL_PLAN §7.3)。 */
    @Test
    void dynamicAddSignerToMultiInstance() {
        repositoryService.createDeployment()
                .name("spike-multi")
                .addClasspathResource("bpmn/spike-multi.bpmn20.xml")
                .tenantId(TENANT)
                .deploy();

        ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId(
                "spikeMulti", "sign-1", Map.of("signers", List.of("u1", "u2")), TENANT);

        long before = taskService.createTaskQuery().processInstanceId(pi.getId()).count();
        assertThat(before).isEqualTo(2);

        // Flowable 的 addMultiInstanceExecution(activityId, parentExecutionId, vars) 期望 parentExecutionId 是
        // MI 根执行的父(顶层 MI 活动即流程实例根执行,其 id == processInstanceId),再由它在子里定位 MI 根。
        runtimeService.addMultiInstanceExecution("sign", pi.getId(), Map.of("signer", "u3"));

        long after = taskService.createTaskQuery().processInstanceId(pi.getId()).count();
        assertThat(after).isEqualTo(3);
    }
}
