package com.knot.backend.document.infrastructure;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.DocumentTopicCancellationService;
import com.knot.backend.document.application.DocumentTopicWorker;
import com.knot.backend.document.application.DocumentTopicRestorationWorker;
import com.knot.backend.document.domain.model.DocumentTopicOperation;
import com.knot.backend.document.domain.model.DocumentTopicOperationKind;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;

class DocumentTopicSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private final DocumentTopicOperationRepository operations = mock(DocumentTopicOperationRepository.class);
    private final DocumentTopicWorker worker = mock(DocumentTopicWorker.class);
    private final DocumentTopicRestorationWorker restorations = mock(DocumentTopicRestorationWorker.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final DocumentTopicCancellationService cancellations = mock(DocumentTopicCancellationService.class);
    private final DocumentTopicScheduler scheduler = new DocumentTopicScheduler(
            operations,
            worker,
            restorations,
            executor,
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            ),
            cancellations
    );

    @BeforeEach
    void setUp() {
        when(operations.findCancellationRequested()).thenReturn(List.of());
        when(operations.findDispatchable(NOW)).thenReturn(List.of());
    }

    @Test
    @DisplayName("LLM 실행 슬롯이 포화되어도 취소 정리를 먼저 처리한다")
    void dispatch_success_cancellationBeforeBusyExecutor() {
        // given
        DocumentTopicOperation pending = operation(1);
        DocumentTopicOperation ready = operation(2);
        when(operations.findCancellationRequested()).thenReturn(List.of(pending));
        when(operations.findDispatchable(NOW)).thenReturn(List.of(ready));
        doThrow(new TaskRejectedException("busy")).when(executor)
                .execute(ArgumentMatchers.any(Runnable.class));
        // when
        scheduler.dispatch();
        // then
        InOrder order = inOrder(
                cancellations,
                executor
        );
        order.verify(cancellations)
                .complete(
                        12,
                        1
                );
        order.verify(executor)
                .execute(ArgumentMatchers.any(Runnable.class));
        verifyNoInteractions(worker);
    }

    @Test
    @DisplayName("한 취소 정리의 저장 오류가 다른 작업 정리를 막지 않는다")
    void dispatch_success_continuesAfterStorageFailure() {
        // given
        DocumentTopicOperation first = operation(1);
        DocumentTopicOperation second = operation(2);
        when(operations.findCancellationRequested()).thenReturn(
                List.of(
                        first,
                        second
                )
        );
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(cancellations)
                .complete(
                        12,
                        1
                );
        // when
        scheduler.dispatch();
        // then
        verify(cancellations).complete(
                12,
                1
        );
        verify(cancellations).complete(
                12,
                2
        );
        verifyNoInteractions(
                executor,
                worker
        );
    }

    @Test
    @DisplayName("복원은 별도 워커로 보내고 재정리만 LLM 워커로 보낸다")
    void dispatch_success_routesOperationKind() {
        // given
        DocumentTopicOperation restore = operation(1);
        DocumentTopicOperation reorganize = operation(2);
        when(restore.getKind()).thenReturn(DocumentTopicOperationKind.RESTORE);
        when(reorganize.getKind()).thenReturn(DocumentTopicOperationKind.REORGANIZE);
        when(operations.findDispatchable(NOW)).thenReturn(
                List.of(
                        restore,
                        reorganize
                )
        );
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor)
                .execute(ArgumentMatchers.any(Runnable.class));
        // when
        scheduler.dispatch();
        // then
        verify(restorations).execute(
                12,
                1
        );
        verify(worker).execute(
                12,
                2
        );
    }

    private DocumentTopicOperation operation(long id) {
        DocumentTopicOperation operation = mock(DocumentTopicOperation.class);
        when(operation.getId()).thenReturn(id);
        when(operation.getWorkspaceId()).thenReturn(12L);
        return operation;
    }
}
