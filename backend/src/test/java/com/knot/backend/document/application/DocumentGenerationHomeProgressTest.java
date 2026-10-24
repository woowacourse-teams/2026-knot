package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentGenerationHomeProgressTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private final DocumentGenerationBatchRepository batches = mock(DocumentGenerationBatchRepository.class);
    private final DocumentGenerationJobRepository jobs = mock(DocumentGenerationJobRepository.class);
    private final DocumentGenerationInputQuery inputs = mock(DocumentGenerationInputQuery.class);
    private final DocumentGenerationProgressService service = new DocumentGenerationProgressService(
            batches,
            jobs,
            inputs
    );

    @Test
    void homeProgress_onlyEligibleFailedJobsAndReadInputOnce() {
        prepareBatch();
        DocumentGenerationJob eligible = failedJob(8L);
        DocumentGenerationJob exhausted = failedJob(9L);
        ReflectionTestUtils.setField(
                exhausted,
                "userRetryCount",
                3
        );
        DocumentGenerationJob expired = failedJob(10L);
        ReflectionTestUtils.setField(
                expired,
                "expiresAt",
                NOW
        );
        DocumentGenerationJob succeeded = failedJob(11L);
        ReflectionTestUtils.setField(
                succeeded,
                "status",
                com.knot.backend.document.domain.model.DocumentGenerationJobStatus.SUCCEEDED
        );
        when(jobs.findAllByBatchId(1L)).thenReturn(
                List.of(
                        eligible,
                        exhausted,
                        expired,
                        succeeded,
                        eligible
                )
        );
        when(
                inputs.find(
                        2L,
                        3L
                )
        ).thenReturn(
                Optional.of(
                        new DocumentGenerationInputResult(
                                3L,
                                4L,
                                "회의 내용"
                        )
                )
        );

        var result = service.findHomeProgress(
                2L,
                4L,
                NOW
        )
                .orElseThrow();

        assertThat(result.hasVisibleFailure()).isTrue();
        assertThat(result.retryableJobIds()).containsExactly(8L);
        assertThat(eligible.getUserRetryCount()).isZero();
        verify(
                inputs,
                times(1)
        ).find(
                2L,
                3L
        );
        verify(
                inputs,
                never()
        ).findForUpdate(
                anyLong(),
                anyLong()
        );
        verify(
                jobs,
                never()
        ).flush();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00A0\u3000"})
    void homeProgress_blankInputKeepsFailureWithoutRetry(String content) {
        prepareBatch();
        when(jobs.findAllByBatchId(1L)).thenReturn(List.of(failedJob(8L)));
        when(
                inputs.find(
                        2L,
                        3L
                )
        ).thenReturn(
                Optional.of(
                        new DocumentGenerationInputResult(
                                3L,
                                4L,
                                content
                        )
                )
        );

        var result = service.findHomeProgress(
                2L,
                4L,
                NOW
        )
                .orElseThrow();

        assertThat(result.hasVisibleFailure()).isTrue();
        assertThat(result.retryableJobIds()).isEmpty();
    }

    @Test
    void homeProgress_missingOrOtherRecordingInputCannotRetry() {
        prepareBatch();
        when(jobs.findAllByBatchId(1L)).thenReturn(List.of(failedJob(8L)));
        when(
                inputs.find(
                        2L,
                        3L
                )
        ).thenReturn(
                Optional.empty(),
                Optional.of(
                        new DocumentGenerationInputResult(
                                3L,
                                99L,
                                "회의"
                        )
                )
        );
        assertThat(
                service.findHomeProgress(
                        2L,
                        4L,
                        NOW
                )
                        .orElseThrow()
                        .retryableJobIds()
        ).isEmpty();
        assertThat(
                service.findHomeProgress(
                        2L,
                        4L,
                        NOW
                )
                        .orElseThrow()
                        .retryableJobIds()
        ).isEmpty();
    }

    @Test
    void homeProgress_expiryEqualityHidesFailure() {
        prepareBatch();
        DocumentGenerationJob job = failedJob(8L);
        when(jobs.findAllByBatchId(1L)).thenReturn(List.of(job));
        var result = service.findHomeProgress(
                2L,
                4L,
                job.getExpiresAt()
        )
                .orElseThrow();
        assertThat(result.hasVisibleFailure()).isFalse();
        assertThat(result.retryableJobIds()).isEmpty();
        verifyNoInteractions(inputs);
    }

    private void prepareBatch() {
        DocumentGenerationBatch batch = mock(DocumentGenerationBatch.class);
        when(batch.getId()).thenReturn(1L);
        when(batch.getProcessingStatus()).thenReturn(DocumentGenerationProcessingStatus.FAILED);
        when(
                batches.findByWorkspaceIdAndRecordingSessionId(
                        2L,
                        4L
                )
        ).thenReturn(Optional.of(batch));
    }

    private DocumentGenerationJob failedJob(long id) {
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                1L,
                3L,
                DocumentTopic.of("설계"),
                NOW.minusSeconds(2)
        );
        job.recordFailure(NOW.minusSeconds(1));
        ReflectionTestUtils.setField(
                job,
                "id",
                id
        );
        return job;
    }
}
