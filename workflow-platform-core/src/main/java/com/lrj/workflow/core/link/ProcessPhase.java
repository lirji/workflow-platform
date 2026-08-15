package com.lrj.workflow.core.link;

/**
 * 流程实例在中台侧的阶段(记在 wf_process_link.phase,配合唯一约束实现 dedup 与并发保护)。
 */
public enum ProcessPhase {
    /** 等待人工节点办理(同 businessKey 最多一个,偏唯一索引保证)。 */
    WAITING_USER,
    /** 人工已决定、等业务系统落地 ACK(不阻塞同 businessKey 的新 cycle)。 */
    WAITING_BUSINESS,
    /** 流程正常结束。 */
    COMPLETED,
    /** 被取消。 */
    CANCELLED,
    /** 进入人工处置(未落地/异常)。 */
    INCIDENT
}
