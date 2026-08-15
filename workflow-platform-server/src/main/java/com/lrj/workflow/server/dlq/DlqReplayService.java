package com.lrj.workflow.server.dlq;

import com.lrj.workflow.core.dlq.DlqEventRepository;
import com.lrj.workflow.core.dlq.DlqRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信重放:把 wf_dlq_event 里 NEW 的消息按原 key/payload 投回原始 topic,由原监听器重新处理,
 * 成功后标记 REPLAYED。原监听器自带 inbox 幂等 → 重放安全。
 */
@Service
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);
    private static final int REPLAY_ALL_CAP = 500;

    private final DlqEventRepository dlq;
    private final KafkaTemplate<String, String> kafka;

    public DlqReplayService(DlqEventRepository dlq, KafkaTemplate<String, String> kafka) {
        this.dlq = dlq;
        this.kafka = kafka;
    }

    public List<DlqRecord> list(String status, int limit) {
        return dlq.findByStatus(status, limit);
    }

    /** 重放单条。返回 false=不存在或已重放。 */
    public boolean replay(long id) {
        DlqRecord rec = dlq.find(id).orElse(null);
        if (rec == null || !"NEW".equals(rec.status())) {
            return false;
        }
        kafka.send(rec.originalTopic(), rec.msgKey(), rec.payload());
        dlq.markReplayed(id);
        log.info("死信重放 id={} → topic={} key={}", id, rec.originalTopic(), rec.msgKey());
        return true;
    }

    /** 批量重放 NEW(上限 {@value #REPLAY_ALL_CAP});返回成功条数。 */
    public int replayAll() {
        int n = 0;
        for (DlqRecord rec : dlq.findByStatus("NEW", REPLAY_ALL_CAP)) {
            if (replay(rec.id())) {
                n++;
            }
        }
        return n;
    }
}
