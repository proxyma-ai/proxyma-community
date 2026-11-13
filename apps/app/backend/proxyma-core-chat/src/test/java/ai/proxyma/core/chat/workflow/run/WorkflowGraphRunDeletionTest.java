package ai.proxyma.core.chat.workflow.run;

import ai.proxyma.core.chat.model.WorkflowGraphRun;
import ai.proxyma.core.chat.repository.WorkflowGraphRunRepository;
import ai.proxyma.core.chat.repository.WorkflowRunEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A WORKFLOW RUN GOES WITH ITS CONVERSATION AND ITS USER (2026-11-12 legal review): a graph run holds its inputs
 * and every step's output, and the repository's delete had no caller, so a deleted conversation or account left its
 * runs and their events behind - while the DPA said account deletion deletes the user's data.
 */
class WorkflowGraphRunDeletionTest {

    private final WorkflowGraphRunRepository runs = mock(WorkflowGraphRunRepository.class);
    private final WorkflowRunEventRepository events = mock(WorkflowRunEventRepository.class);
    private final WorkflowGraphRunService service = new WorkflowGraphRunService(runs, events, null, null,
            null, null, null, null, null, null);

    private static WorkflowGraphRun run(UUID id) {
        WorkflowGraphRun r = new WorkflowGraphRun();
        r.setId(id);
        return r;
    }

    @Test
    @DisplayName("a conversation's runs are deleted with their events")
    void withTheConversation() {
        UUID a = UUID.randomUUID();
        when(runs.findByConversationId("c1")).thenReturn(List.of(run(a)));

        service.deleteForConversation("c1");

        verify(events).deleteByRunId(a);
        verify(runs).deleteById(a);
    }

    @Test
    @DisplayName("every run a user started, in a chat or not, is deleted with the account")
    void withTheUser() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        when(runs.findByUserId("u1")).thenReturn(List.of(run(a), run(b)));

        service.deleteForUser("u1");

        verify(events).deleteByRunId(a);
        verify(events).deleteByRunId(b);
        verify(runs).deleteById(a);
        verify(runs).deleteById(b);
    }
}
