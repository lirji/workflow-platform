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

    public Optional<ProcessInstanceView> getInstance(String processInstanceId) {
        return linkRepo.findByInstanceId(processInstanceId).map(this::toView);
    }

    public List<TimelineEntry> timeline(String processInstanceId) {
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

    private ProcessInstanceView toView(ProcessLink l) {
        boolean running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(l.processInstanceId()).count() > 0;
        return new ProcessInstanceView(l.processInstanceId(), l.tenantId(), l.processDefinitionKey(),
                l.businessKey(), l.idempotencyKey(), l.phase().name(), l.status(), running);
    }
}
