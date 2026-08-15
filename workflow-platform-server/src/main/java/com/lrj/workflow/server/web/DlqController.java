package com.lrj.workflow.server.web;

import com.lrj.workflow.core.dlq.DlqRecord;
import com.lrj.workflow.server.dlq.DlqReplayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 死信运维 REST(排查 + 重放)。鉴权启用时同 /api/** 需认证(P1 再收敛到管理员角色 + admin 面板)。
 */
@RestController
@RequestMapping("/api/v1/dlq")
public class DlqController {

    private final DlqReplayService dlq;

    public DlqController(DlqReplayService dlq) {
        this.dlq = dlq;
    }

    @GetMapping
    public List<DlqRecord> list(@RequestParam(defaultValue = "NEW") String status,
                                @RequestParam(defaultValue = "100") int limit) {
        return dlq.list(status, limit);
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Map<String, Object>> replay(@PathVariable long id) {
        boolean ok = dlq.replay(id);
        return ok
                ? ResponseEntity.ok(Map.of("id", id, "status", "REPLAYED"))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("id", id, "error", "NOT_FOUND_OR_ALREADY_REPLAYED"));
    }

    @PostMapping("/replay-all")
    public Map<String, Integer> replayAll() {
        return Map.of("replayed", dlq.replayAll());
    }
}
