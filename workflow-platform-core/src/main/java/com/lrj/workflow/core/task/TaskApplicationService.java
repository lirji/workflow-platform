package com.lrj.workflow.core.task;

import com.lrj.workflow.core.WorkflowAccessDeniedException;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.core.link.ProcessPhaseTransitionService;
import com.lrj.workflow.protocol.api.TaskView;
import com.lrj.workflow.protocol.event.Actor;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 任务办理。complete 与"delegate 写 outbox""link 阶段更新"同一事务原子提交(Flowable 用 Spring 事务)。
 */
@Service
public class TaskApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TaskApplicationService.class);

    private final TaskService taskService;
    private final RuntimeService runtimeService;
    private final ProcessLinkRepository linkRepo;
    private final ProcessPhaseTransitionService phaseTransitions;

    public TaskApplicationService(TaskService taskService, RuntimeService runtimeService,
                                  ProcessLinkRepository linkRepo, ProcessPhaseTransitionService phaseTransitions) {
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.linkRepo = linkRepo;
        this.phaseTransitions = phaseTransitions;
    }

    /** 校验任务在租户下存在,否则抛 FlowableObjectNotFoundException(→404)。 */
    private Task requireTenantTask(String tenant, String taskId) {
        Task t = taskService.createTaskQuery().taskId(taskId).taskTenantId(tenant).singleResult();
        if (t == null) {
            throw new org.flowable.common.engine.api.FlowableObjectNotFoundException("任务不存在: " + taskId, Task.class);
        }
        return t;
    }

    private Task requireAuthorizedTask(String tenant, String taskId, TaskAccessContext access) {
        Task task = requireTenantTask(tenant, taskId);
        if (!canAccess(task, access)) {
            throw new WorkflowAccessDeniedException("当前用户无权操作该任务");
        }
        return task;
    }

    private Task requireAssigneeOrAdmin(String tenant, String taskId, TaskAccessContext access) {
        Task task = requireTenantTask(tenant, taskId);
        if (!access.enforced() || access.isAdmin()) {
            return task;
        }
        if (access.principalId() == null || !access.principalId().equals(task.getAssignee())) {
            throw new WorkflowAccessDeniedException("仅当前办理人可以执行该任务操作");
        }
        return task;
    }

    private boolean canAccess(Task task, TaskAccessContext access) {
        if (!access.enforced() || access.isAdmin()) {
            return true;
        }
        if (access.principalId() == null || access.principalId().isBlank()) {
            return false;
        }
        if (task.getAssignee() != null && !task.getAssignee().isBlank()) {
            return access.principalId().equals(task.getAssignee());
        }
        return taskService.getIdentityLinksForTask(task.getId()).stream().anyMatch(link ->
                access.principalId().equals(link.getUserId())
                        || (link.getGroupId() != null
                        && access.authorities().contains(link.getGroupId().toUpperCase(java.util.Locale.ROOT))));
    }

    /** 认领:安全开启时普通用户走 Flowable 原子 claim，防止两个候选人并发覆盖办理人。 */
    public void claimTask(String tenant, String taskId, String userId, TaskAccessContext access) {
        Task task = requireTenantTask(tenant, taskId);
        if (access.enforced() && !access.isAdmin()) {
            if (access.principalId() == null || !access.principalId().equals(userId)) {
                throw new WorkflowAccessDeniedException("只能为当前用户认领任务");
            }
            if (task.getAssignee() != null && !task.getAssignee().isBlank()
                    && !access.principalId().equals(task.getAssignee())) {
                throw new WorkflowAccessDeniedException("任务已被其他用户认领");
            }
            if ((task.getAssignee() == null || task.getAssignee().isBlank()) && !canAccess(task, access)) {
                throw new WorkflowAccessDeniedException("当前用户不是任务候选人");
            }
        }
        if (access.enforced() && !access.isAdmin()) {
            if (userId.equals(task.getAssignee())) {
                return;
            }
            taskService.claim(taskId, userId);
            return;
        }
        taskService.setAssignee(taskId, userId);
    }

    /** 转办:改办理人为 assignee。 */
    public void reassignTask(String tenant, String taskId, String assignee, TaskAccessContext access) {
        requireAssigneeOrAdmin(tenant, taskId, access);
        taskService.setAssignee(taskId, assignee);
    }

    /** 委派:委派给 userId(委派后由其办理并 resolve 回原办理人)。 */
    public void delegateTask(String tenant, String taskId, String userId, TaskAccessContext access) {
        requireAssigneeOrAdmin(tenant, taskId, access);
        taskService.delegateTask(taskId, userId);
    }

    /** 撤回认领:清空办理人,任务回到候选池。 */
    public void unclaimTask(String tenant, String taskId, TaskAccessContext access) {
        requireAssigneeOrAdmin(tenant, taskId, access);
        taskService.unclaim(taskId);
    }

    /** 按租户 + (可选)流程定义 key + businessKey 查活动任务。shadow 镜像与待办中心共用。 */
    public List<TaskView> findTasks(String tenant, String definitionKey, String businessKey,
                                    TaskAccessContext access) {
        var q = taskService.createTaskQuery().taskTenantId(tenant);
        if (definitionKey != null && !definitionKey.isBlank()) {
            q = q.processDefinitionKey(definitionKey);
        }
        if (businessKey != null && !businessKey.isBlank()) {
            q = q.processInstanceBusinessKey(businessKey);
        }
        return q.orderByTaskCreateTime().asc().list().stream()
                .filter(t -> canAccess(t, access))
                .map(this::toView).toList();
    }

    /**
     * 待办分页查询(console 用):在 findTasks 基础上按候选组过滤 + 分页。候选组匹配做归一化(大小写不敏感 +
     * 去 {@code path/} 前缀),以对齐 BPMN candidateGroups 与 JWT 精确组名。
     */
    public com.lrj.workflow.protocol.api.TaskSearchResult searchTasks(
            String tenant, String definitionKey, String businessKey,
            List<String> candidateGroups, int page, int size, TaskAccessContext access) {
        List<TaskView> all = findTasks(tenant, definitionKey, businessKey, access);
        // 安全开启时，普通用户的候选组只能来自 JWT；不能信任 query 参数扩大结果集。
        if ((!access.enforced() || access.isAdmin()) && candidateGroups != null && !candidateGroups.isEmpty()) {
            java.util.Set<String> want = candidateGroups.stream().map(TaskApplicationService::normalizeGroup)
                    .collect(java.util.stream.Collectors.toSet());
            all = all.stream().filter(t -> t.candidateGroups() != null && t.candidateGroups().stream()
                    .map(TaskApplicationService::normalizeGroup).anyMatch(want::contains)).toList();
        }
        long total = all.size();
        int from = Math.max(0, page) * Math.max(1, size);
        int to = Math.min(all.size(), from + Math.max(1, size));
        List<TaskView> pageItems = from >= all.size() ? List.of() : all.subList(from, to);
        return new com.lrj.workflow.protocol.api.TaskSearchResult(pageItems, total, page, size);
    }

    static String normalizeGroup(String g) {
        if (g == null) {
            return "";
        }
        String s = g;
        int slash = s.lastIndexOf('/');
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        return s.toLowerCase();
    }

    private TaskView toView(Task t) {
        List<String> groups = taskService.getIdentityLinksForTask(t.getId()).stream()
                .map(IdentityLink::getGroupId).filter(g -> g != null).distinct().toList();
        // 流程定义 id 形如 "key:version:genId",取 key;businessKey 从 link 反查(可靠)
        String pdKey = t.getProcessDefinitionId() == null ? null : t.getProcessDefinitionId().split(":")[0];
        String businessKey = linkRepo.findByInstanceId(t.getProcessInstanceId())
                .map(l -> l.businessKey()).orElse(null);
        return new TaskView(t.getId(), t.getTaskDefinitionKey(), t.getName(), t.getProcessInstanceId(),
                pdKey, businessKey, t.getTenantId(), t.getAssignee(), groups,
                t.getCreateTime() == null ? null : t.getCreateTime().getTime());
    }

    /**
     * 审方办理(通过/驳回)。decision ∈ {PASS, REJECT}。生成 actionId 作业务幂等键。
     * @return 生成的 actionId
     */
    @Transactional
    public String completeReview(String taskId, String tenant, String decision, String opinion, Actor actor,
                                 TaskAccessContext access) {
        if (!"PASS".equals(decision) && !"REJECT".equals(decision)) {
            throw new IllegalArgumentException("decision 必须是 PASS 或 REJECT: " + decision);
        }
        Task task = requireAuthorizedTask(tenant, taskId, access);
        String actionId = UUID.randomUUID().toString();
        Map<String, Object> vars = new HashMap<>();
        vars.put("decision", decision);
        vars.put("opinion", opinion);
        vars.put("actionId", actionId);
        vars.put("completedTaskId", taskId);
        vars.put("actorSub", actor == null ? null : actor.subjectId());
        vars.put("actorUsername", actor == null ? null : actor.username());
        vars.put("actorDisplayName", actor == null ? null : actor.displayName());

        String instanceId = task.getProcessInstanceId();
        // 驱动 BPMN:结论网关 → prepareAction(delegate 写 outbox)→ 泊在 waitApplied message catch
        taskService.complete(taskId, vars);

        transitionAfterComplete(instanceId);
        log.info("审方办理完成 taskId={} decision={} actionId={}", taskId, decision, actionId);
        return actionId;
    }

    /** 完成后据流程当前形态推进 link 阶段:结束→COMPLETED;仍有人工任务→WAITING_USER;否则(泊在 message)→WAITING_BUSINESS。 */
    private void transitionAfterComplete(String instanceId) {
        ProcessPhase next;
        boolean ended = runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).count() == 0;
        if (ended) {
            next = ProcessPhase.COMPLETED;
        } else {
            boolean hasTask = taskService.createTaskQuery().processInstanceId(instanceId).count() > 0;
            next = hasTask ? ProcessPhase.WAITING_USER : ProcessPhase.WAITING_BUSINESS;
        }
        phaseTransitions.transition(instanceId, next);
    }
}
