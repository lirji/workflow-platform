package com.lrj.workflow.core.process;

import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.protocol.api.ProcessInstanceView;
import com.lrj.workflow.protocol.api.TimelineEntry;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 流程实例只读查询(console 用):实例视图(阶段)+ 历史轨迹。不改任何状态。
 */
@Service
public class ProcessQueryService {

    private final ProcessLinkRepository linkRepo;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;

    public ProcessQueryService(ProcessLinkRepository linkRepo, HistoryService historyService,
                               RuntimeService runtimeService) {
        this.linkRepo = linkRepo;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
    }

    public List<ProcessInstanceView> findByBusinessKey(String tenant, String defKey, String bizKey) {
        return linkRepo.findByBusinessKey(tenant, defKey, bizKey).stream().map(this::toView).toList();
    }

    /** 运维查询:按租户 +(可选)定义 key +(可选)阶段 列实例。 */
    public List<ProcessInstanceView> search(String tenant, String defKey, String phase, int limit) {
        return linkRepo.search(tenant, defKey, phase, limit).stream().map(this::toView).toList();
    }

    public Optional<ProcessInstanceView> getInstance(String tenant, String processInstanceId) {
        return linkRepo.findByTenantAndInstanceId(tenant, processInstanceId).map(this::toView);
    }

    /**
     * 按租户、实例和预期 definition key 定位该实例实际运行的 definition id。
     * 运行实例优先查 runtime；已结束实例回退 history，供轨迹页加载准确版本的 BPMN XML。
     */
    public Optional<String> processDefinitionId(String tenant, String processInstanceId, String expectedDefinitionKey) {
        ProcessLink link = linkRepo.findByTenantAndInstanceId(tenant, processInstanceId).orElse(null);
        if (link == null || !java.util.Objects.equals(link.processDefinitionKey(), expectedDefinitionKey)) {
            return Optional.empty();
        }
        var running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (running != null) {
            return Optional.ofNullable(running.getProcessDefinitionId());
        }
        var historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return historic == null ? Optional.empty() : Optional.ofNullable(historic.getProcessDefinitionId());
    }

    private List<TimelineEntry> timelineForInstance(String processInstanceId) {
        return historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list().stream()
                .map(a -> new TimelineEntry(
                        a.getActivityId(), a.getActivityName(), a.getActivityType(), a.getAssignee(),
                        a.getStartTime() == null ? null : a.getStartTime().getTime(),
                        a.getEndTime() == null ? null : a.getEndTime().getTime()))
                .toList();
    }

    public List<TimelineEntry> timeline(String tenant, String processInstanceId) {
        if (linkRepo.findByTenantAndInstanceId(tenant, processInstanceId).isEmpty()) {
            throw new org.flowable.common.engine.api.FlowableObjectNotFoundException(
                    "流程实例不存在", org.flowable.engine.runtime.ProcessInstance.class);
        }
        return timelineForInstance(processInstanceId);
    }

    private ProcessInstanceView toView(ProcessLink l) {
        org.flowable.engine.runtime.ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceId(l.processInstanceId()).singleResult();
        boolean running = pi != null;
        boolean suspended = pi != null && pi.isSuspended();
        return new ProcessInstanceView(l.processInstanceId(), l.tenantId(), l.processDefinitionKey(),
                l.businessKey(), l.idempotencyKey(), l.phase().name(), l.status(), running, suspended);
    }
}
