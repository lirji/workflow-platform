package com.lrj.workflow.server;

import com.lrj.workflow.core.dlq.DlqRecord;
import com.lrj.workflow.server.dlq.DlqReplayService;
import com.lrj.workflow.server.web.DlqController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DlqController Web 层测试:列出死信 + 重放 200/404。 */
@WebMvcTest(DlqController.class)
@AutoConfigureMockMvc(addFilters = false)
class DlqControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DlqReplayService dlq;

    @Test
    void listReturnsRecords() throws Exception {
        when(dlq.list("NEW", 100)).thenReturn(List.of(
                new DlqRecord(1L, "workflow.command.start.v1", "k1", "{}", "boom", "NEW", 1L, null)));
        mvc.perform(get("/api/v1/dlq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].originalTopic").value("workflow.command.start.v1"))
                .andExpect(jsonPath("$[0].status").value("NEW"));
    }

    @Test
    void replayOk() throws Exception {
        when(dlq.replay(5L)).thenReturn(true);
        mvc.perform(post("/api/v1/dlq/5/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"));
    }

    @Test
    void replayNotFound() throws Exception {
        when(dlq.replay(404L)).thenReturn(false);
        mvc.perform(post("/api/v1/dlq/404/replay"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND_OR_ALREADY_REPLAYED"));
    }
}
