package com.lrj.workflow.core.link;

import com.lrj.workflow.core.WorkflowConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对 link phase 做有限重读 CAS，避免 Flowable 已推进而一次乐观锁失败被静默忽略。
 */
@Service
public class ProcessPhaseTransitionService {

    private static final Logger log = LoggerFactory.getLogger(ProcessPhaseTransitionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final ProcessLinkRepository links;

    public ProcessPhaseTransitionService(ProcessLinkRepository links) {
        this.links = links;
    }

    /**
     * @return 更新后的 link；若已经被 admin 置为不可逆终态，则保留并返回该终态。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProcessLink transition(String processInstanceId, ProcessPhase target) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ProcessLink current = links.findByInstanceId(processInstanceId)
                    .orElseThrow(() -> new WorkflowConflictException("流程 link 不存在: " + processInstanceId));
            if (current.phase() == target) {
                return current;
            }
            if (current.phase() == ProcessPhase.COMPLETED || current.phase() == ProcessPhase.CANCELLED) {
                log.info("保留流程不可逆终态 pid={} current={} requested={}",
                        processInstanceId, current.phase(), target);
                return current;
            }
            if (current.phase() == ProcessPhase.INCIDENT && target != ProcessPhase.COMPLETED) {
                throw new WorkflowConflictException(
                        "INCIDENT 只能由人工处置完成为 COMPLETED，不能回退到 " + target);
            }
            if (links.updatePhase(processInstanceId, target, current.version())) {
                return links.findByInstanceId(processInstanceId)
                        .orElseThrow(() -> new WorkflowConflictException("阶段更新后流程 link 消失: " + processInstanceId));
            }
            log.debug("流程阶段 CAS 冲突，重读重试 pid={} target={} attempt={}",
                    processInstanceId, target, attempt);
        }
        throw new WorkflowConflictException(
                "流程阶段并发更新连续冲突，拒绝静默丢失: pid=" + processInstanceId + ", target=" + target);
    }
}
