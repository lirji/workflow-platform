package com.lrj.workflow.core.link;

import com.lrj.workflow.core.WorkflowConflictException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessPhaseTransitionServiceTest {

    private static ProcessLink link(ProcessPhase phase, String status, long version) {
        return new ProcessLink(1, "his", "hisRxReview", "enc-1", "cycle-1", "pi-1",
                phase, status, version);
    }

    @Test
    void rereadsAndRetriesAfterOptimisticConflict() {
        ProcessLinkRepository repository = mock(ProcessLinkRepository.class);
        when(repository.findByInstanceId("pi-1"))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_USER, "ACTIVE", 0)))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_USER, "ACTIVE", 1)))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_BUSINESS, "ACTIVE", 2)));
        when(repository.updatePhase("pi-1", ProcessPhase.WAITING_BUSINESS, 0)).thenReturn(false);
        when(repository.updatePhase("pi-1", ProcessPhase.WAITING_BUSINESS, 1)).thenReturn(true);

        ProcessLink result = new ProcessPhaseTransitionService(repository)
                .transition("pi-1", ProcessPhase.WAITING_BUSINESS);

        assertThat(result.phase()).isEqualTo(ProcessPhase.WAITING_BUSINESS);
        assertThat(result.version()).isEqualTo(2);
    }

    @Test
    void doesNotOverwriteAdminTerminalState() {
        ProcessLinkRepository repository = mock(ProcessLinkRepository.class);
        ProcessLink cancelled = link(ProcessPhase.CANCELLED, "ENDED", 3);
        when(repository.findByInstanceId("pi-1")).thenReturn(Optional.of(cancelled));

        assertThat(new ProcessPhaseTransitionService(repository)
                .transition("pi-1", ProcessPhase.COMPLETED)).isEqualTo(cancelled);
        verify(repository, never()).updatePhase("pi-1", ProcessPhase.COMPLETED, 3);
    }

    @Test
    void repeatedCasConflictFailsVisibly() {
        ProcessLinkRepository repository = mock(ProcessLinkRepository.class);
        when(repository.findByInstanceId("pi-1"))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_USER, "ACTIVE", 0)))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_USER, "ACTIVE", 1)))
                .thenReturn(Optional.of(link(ProcessPhase.WAITING_USER, "ACTIVE", 2)));

        assertThatThrownBy(() -> new ProcessPhaseTransitionService(repository)
                .transition("pi-1", ProcessPhase.WAITING_BUSINESS))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("拒绝静默丢失");
        verify(repository).updatePhase("pi-1", ProcessPhase.WAITING_BUSINESS, 0);
        verify(repository).updatePhase("pi-1", ProcessPhase.WAITING_BUSINESS, 1);
        verify(repository).updatePhase("pi-1", ProcessPhase.WAITING_BUSINESS, 2);
    }

    @Test
    void incidentCannotRegressButCanCompleteAfterManualRepair() {
        ProcessLinkRepository repository = mock(ProcessLinkRepository.class);
        when(repository.findByInstanceId("pi-1"))
                .thenReturn(Optional.of(link(ProcessPhase.INCIDENT, "ERROR", 4)));

        assertThatThrownBy(() -> new ProcessPhaseTransitionService(repository)
                .transition("pi-1", ProcessPhase.WAITING_BUSINESS))
                .isInstanceOf(WorkflowConflictException.class)
                .hasMessageContaining("INCIDENT 只能");

        when(repository.updatePhase("pi-1", ProcessPhase.COMPLETED, 4)).thenReturn(true);
        when(repository.findByInstanceId("pi-1"))
                .thenReturn(Optional.of(link(ProcessPhase.INCIDENT, "ERROR", 4)))
                .thenReturn(Optional.of(link(ProcessPhase.COMPLETED, "ENDED", 5)));
        assertThat(new ProcessPhaseTransitionService(repository).transition("pi-1", ProcessPhase.COMPLETED).phase())
                .isEqualTo(ProcessPhase.COMPLETED);
    }
}
