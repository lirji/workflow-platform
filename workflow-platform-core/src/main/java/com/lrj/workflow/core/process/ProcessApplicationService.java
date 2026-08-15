package com.lrj.workflow.core.process;

import com.lrj.workflow.core.WorkflowConflictException;
import com.lrj.workflow.core.link.ProcessLink;
import com.lrj.workflow.core.link.ProcessLinkRepository;
import com.lrj.workflow.core.link.ProcessPhase;
import com.lrj.workflow.protocol.event.StartProcessCommandV1;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 发起/查询流程。直接用 Flowable RuntimeService(不抽象引擎,ADR)。
 *
 * <p>幂等发起:四元组(tenant,definition,businessKey,idempotencyKey)唯一。并发同 idempotencyKey →
 * 用 DB 唯一约束当锁:起流程 + 插 link 放同一事务,重复者插入失败→回滚(连带撤销 Flowable start)→再读返回赢家。
 * 同 businessKey 已有 WAITING_USER 的另一 cycle → 偏唯一索引拒绝 → 抛冲突(而非幂等返回)。
 */
@Service
public class ProcessApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ProcessApplicationService.class);

    private final RuntimeService runtimeService;
    private final ProcessLinkRepository linkRepo;
    private final TransactionTemplate tx;

    public ProcessApplicationService(RuntimeService runtimeService, ProcessLinkRepository linkRepo,
                                     TransactionTemplate tx) {
        this.runtimeService = runtimeService;
        this.linkRepo = linkRepo;
        this.tx = tx;
    }

    public ProcessLink start(String tenant, StartProcessCommandV1 cmd) {
        // 幂等快路径
        var existing = linkRepo.findByIdempotency(tenant, cmd.processDefinitionKey(), cmd.businessKey(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            log.debug("幂等命中,返回原实例 idem={} pi={}", cmd.idempotencyKey(), existing.get().processInstanceId());
            return existing.get();
        }
        try {
            return tx.execute(status -> {
                Map<String, Object> vars = new HashMap<>();
                if (cmd.variables() != null) {
                    vars.putAll(cmd.variables());
                }
                vars.put("idempotencyKey", cmd.idempotencyKey());
                vars.put("processDefinitionKey", cmd.processDefinitionKey());
                ProcessInstance pi = runtimeService.startProcessInstanceByKeyAndTenantId(
                        cmd.processDefinitionKey(), cmd.businessKey(), vars, tenant);
                linkRepo.insert(tenant, cmd.processDefinitionKey(), cmd.businessKey(), cmd.idempotencyKey(),
                        pi.getId(), ProcessPhase.WAITING_USER, "ACTIVE");
                return linkRepo.findByInstanceId(pi.getId()).orElseThrow();
            });
        } catch (DuplicateKeyException e) {
            // 事务已回滚(撤销 Flowable start)。区分:四元组重复=幂等赢家;否则=WAITING_USER 冲突。
            var winner = linkRepo.findByIdempotency(tenant, cmd.processDefinitionKey(), cmd.businessKey(), cmd.idempotencyKey());
            if (winner.isPresent()) {
                log.info("并发幂等发起,返回赢家 idem={} pi={}", cmd.idempotencyKey(), winner.get().processInstanceId());
                return winner.get();
            }
            throw new WorkflowConflictException(
                    "同 businessKey 已有活动人工待办(WAITING_USER),不能再起新 cycle: " + cmd.businessKey());
        }
    }
}
