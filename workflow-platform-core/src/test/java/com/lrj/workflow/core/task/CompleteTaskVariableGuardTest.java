package com.lrj.workflow.core.task;

import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhaseTransitionService;
import com.lrj.workflow.protocol.event.Actor;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通用办理 completeTask 的信任边界:消费方传来的 variables <b>不能</b>成为伪造办理人的通道。
 *
 * <p>这是本方法与 completeReview 最大的差别 —— 后者不接受任意变量,前者接受,
 * 所以"哪些 key 会被丢弃"必须由测试守住,而不是靠注释提醒。
 */
class CompleteTaskVariableGuardTest {

    private TaskApplicationService newService(TaskService taskService) {
        RuntimeService runtime = mock(RuntimeService.class);
        ProcessInstanceQuery q = mock(ProcessInstanceQuery.class);
        when(runtime.createProcessInstanceQuery()).thenReturn(q);
        when(q.processInstanceId(anyString())).thenReturn(q);
        when(q.count()).thenReturn(0L);
        return new TaskApplicationService(taskService, runtime,
                mock(ProcessLinkRepository.class), mock(ProcessPhaseTransitionService.class));
    }

    private TaskService taskServiceReturning(Task task) {
        TaskService ts = mock(TaskService.class);
        TaskQuery tq = mock(TaskQuery.class);
        when(ts.createTaskQuery()).thenReturn(tq);
        when(tq.taskId(anyString())).thenReturn(tq);
        when(tq.taskTenantId(anyString())).thenReturn(tq);
        when(tq.processInstanceId(anyString())).thenReturn(tq);
        when(tq.singleResult()).thenReturn(task);
        when(tq.count()).thenReturn(0L);
        return ts;
    }

    @Test
    @SuppressWarnings("unchecked")
    void reservedVariablesFromCallerAreDroppedAndOverwrittenByServer() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t1");
        when(task.getProcessInstanceId()).thenReturn("pi1");
        TaskService ts = taskServiceReturning(task);
        TaskApplicationService svc = newService(ts);

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("actorSub", "forged-sub");        // 想冒充别人办理
        hostile.put("actionId", "forged-action");     // 想复用幂等键
        hostile.put("decision", "PASS");              // 想让审方结论网关误读
        hostile.put("leaveDays", 3);                  // 正常业务变量,应保留

        String actionId = svc.completeTask("t1", "oa", "APPROVE", "ok", hostile,
                new Actor("real-sub", "zhangsan", "Zhang San"), TaskAccessContext.disabled());

        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(ts).complete(anyString(), vars.capture());
        Map<String, Object> sent = vars.getValue();

        assertThat(sent.get("actorSub")).isEqualTo("real-sub");
        assertThat(sent.get("actionId")).isEqualTo(actionId).isNotEqualTo("forged-action");
        assertThat(sent).doesNotContainKey("decision");   // 审方专用变量不会被通用办理注入
        assertThat(sent.get("leaveDays")).isEqualTo(3);   // 正常业务变量原样透传
        assertThat(sent.get("outcome")).isEqualTo("APPROVE");
    }

    @Test
    void blankOutcomeIsRejected() {
        TaskApplicationService svc = newService(taskServiceReturning(mock(Task.class)));
        assertThatThrownBy(() -> svc.completeTask("t1", "oa", "  ", null, null, null,
                TaskAccessContext.disabled()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }
}
