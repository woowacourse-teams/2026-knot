package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import com.knot.backend.document.application.dto.result.DocumentGenerationExecution;
import com.knot.backend.document.domain.model.DocumentGenerationFailureCause;
import com.knot.backend.document.domain.model.DocumentGenerationJobStage;
import com.knot.backend.global.exception.LlmErrorCode;
import com.knot.backend.global.exception.LlmException;
import com.knot.backend.testsupport.LogCapture;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentGenerationWorkerLoggingTest {

    private final DocumentGenerationClaimService claims = mock(DocumentGenerationClaimService.class);
    private final DocumentTopicClassificationService classifier = mock(DocumentTopicClassificationService.class);
    private final DocumentGenerationFailureService failures = mock(DocumentGenerationFailureService.class);
    private final DocumentGenerationWorker worker = new DocumentGenerationWorker(
            claims,
            classifier,
            mock(DocumentGenerationService.class),
            mock(DocumentClassificationResultService.class),
            mock(DocumentGenerationResultService.class),
            failures,
            new com.knot.backend.global.config.DocumentCategoryProperties(),
            mock(DocumentCategoryCheckpointService.class),
            mock(DocumentCategoryAssignmentService.class)
    );

    @Test
    @DisplayName("생성 실패 뒤 실패 상태 저장도 실패하면 두 원인을 각각 한 번 기록한다")
    void execute_failure_generationAndPersistence() {
        when(
                claims.claim(
                        1,
                        2
                )
        ).thenReturn(Optional.of(execution()));
        when(classifier.classify("private-transcript")).thenThrow(new IllegalStateException("private-provider"));
        doThrow(new IllegalArgumentException("private-sql")).when(failures)
                .failAttempt(
                        1,
                        2,
                        3,
                        DocumentGenerationFailureCause.INTERNAL
                );
        try (LogCapture logs = new LogCapture(DocumentGenerationWorker.class)) {
            worker.execute(
                    1,
                    2
            );
            assertThat(logs.events()).hasSize(2)
                    .allSatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                        assertThat(event.getFormattedMessage()).contains(
                                "workspaceId=1",
                                "jobId=2",
                                "attempt=3",
                                "CLASSIFICATION"
                        )
                                .doesNotContain(
                                        "private-transcript",
                                        "private-provider",
                                        "private-sql"
                                );
                        assertThat(event.getThrowableProxy()).isNull();
                    });
            assertThat(logs.events()).extracting(event -> event.getFormattedMessage())
                    .anySatisfy(message -> assertThat(message).contains("IllegalStateException"))
                    .anySatisfy(message -> assertThat(message).contains("IllegalArgumentException"));
        }
        verify(failures).failAttempt(
                1,
                2,
                3,
                DocumentGenerationFailureCause.INTERNAL
        );
    }

    @Test
    @DisplayName("외부 타임아웃은 WARN이며 실패 상태 매핑은 유지한다")
    void execute_failure_timeoutWarn() {
        when(
                claims.claim(
                        1,
                        2
                )
        ).thenReturn(Optional.of(execution()));
        when(classifier.classify("private-transcript")).thenThrow(
                new LlmException(
                        LlmErrorCode.LLM_TIMEOUT,
                        new java.util.concurrent.TimeoutException("private")
                )
        );
        try (LogCapture logs = new LogCapture(DocumentGenerationWorker.class)) {
            worker.execute(
                    1,
                    2
            );
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("TimeoutException");
                    });
        }
        verify(failures).failAttempt(
                1,
                2,
                3,
                DocumentGenerationFailureCause.TIMEOUT
        );
    }

    @Test
    @DisplayName("정상 호출 중단은 ERROR 없이 기존 TIMEOUT 상태로 저장한다")
    void execute_success_interruptDebug() {
        when(
                claims.claim(
                        1,
                        2
                )
        ).thenReturn(Optional.of(execution()));
        when(classifier.classify("private-transcript")).thenThrow(
                new LlmException(
                        LlmErrorCode.LLM_CALL_INTERRUPTED,
                        new InterruptedException("private")
                )
        );
        try (LogCapture logs = new LogCapture(DocumentGenerationWorker.class)) {
            worker.execute(
                    1,
                    2
            );
            assertThat(logs.events()).singleElement()
                    .satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        }
        verify(failures).failAttempt(
                1,
                2,
                3,
                DocumentGenerationFailureCause.TIMEOUT
        );
    }

    private DocumentGenerationExecution execution() {
        return new DocumentGenerationExecution(
                1,
                2,
                3,
                DocumentGenerationJobStage.CLASSIFICATION,
                "private-transcript",
                null
        );
    }
}
