package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.dto.result.DocumentGenerationInputResult;
import com.knot.backend.document.application.dto.result.DocumentGenerationStatusResult;
import com.knot.backend.document.domain.model.DocumentGenerationBatch;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.DocumentGenerationJobStatus;
import com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus;
import com.knot.backend.document.domain.model.DocumentTopic;
import com.knot.backend.document.domain.repository.DocumentGenerationBatchRepository;
import com.knot.backend.document.domain.repository.DocumentGenerationJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentGenerationRecordingProgressTest {
    private static final long WORKSPACE_ID = 2L;
    private static final long RECORDING_ID = 4L;
    private static final long TRANSCRIPT_ID = 3L;
    private static final long BATCH_ID = 1L;
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
    @DisplayName("녹음 문서는 홈 표시 기한이 지난 실패 Job도 지금 접수할 수 있으면 중복 없이 재시도 대상으로 준다")
    void findRecordingProgress_success_retryableJobsRegardlessOfHomeExpiry() {
        // given
        batch(DocumentGenerationProcessingStatus.FAILED);
        DocumentGenerationJob visible = failedJob(
                8L,
                TRANSCRIPT_ID
        );
        DocumentGenerationJob expired = failedJob(
                10L,
                TRANSCRIPT_ID
        );
        ReflectionTestUtils.setField(
                expired,
                "expiresAt",
                NOW.minus(Duration.ofDays(30))
        );
        DocumentGenerationJob succeeded = failedJob(
                11L,
                TRANSCRIPT_ID
        );
        ReflectionTestUtils.setField(
                succeeded,
                "status",
                DocumentGenerationJobStatus.SUCCEEDED
        );
        DocumentGenerationJob unusableInput = failedJob(
                12L,
                99L
        );
        when(jobs.findAllByBatchId(BATCH_ID)).thenReturn(
                List.of(
                        expired,
                        visible,
                        succeeded,
                        unusableInput,
                        visible
                )
        );
        when(
                inputs.find(
                        WORKSPACE_ID,
                        TRANSCRIPT_ID
                )
        ).thenReturn(
                Optional.of(
                        new DocumentGenerationInputResult(
                                TRANSCRIPT_ID,
                                RECORDING_ID,
                                "회의 내용"
                        )
                )
        );
        when(
                inputs.find(
                        WORKSPACE_ID,
                        99L
                )
        ).thenReturn(Optional.empty());

        // when
        DocumentGenerationStatusResult result = findRecordingProgress().orElseThrow();

        // then
        assertThat(result.status()).isEqualTo(DocumentGenerationProcessingStatus.FAILED);
        assertThat(result.retryableJobIds()).containsExactly(
                8L,
                10L
        );
    }

    @ParameterizedTest
    @EnumSource(value = DocumentGenerationProcessingStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("실패로 끝나지 않은 문서 정리는 재시도 대상 없이 상태만 준다")
    void findRecordingProgress_success_notFailed(DocumentGenerationProcessingStatus status) {
        // given
        batch(status);

        // when
        DocumentGenerationStatusResult result = findRecordingProgress().orElseThrow();

        // then
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.retryableJobIds()).isEmpty();
        verifyNoInteractions(
                jobs,
                inputs
        );
    }

    @Test
    @DisplayName("문서 정리가 접수되지 않은 녹음은 결과가 없다")
    void findRecordingProgress_success_noBatch() {
        // given
        when(
                batches.findByWorkspaceIdAndRecordingSessionId(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(Optional.empty());

        // when
        Optional<DocumentGenerationStatusResult> result = findRecordingProgress();

        // then
        assertThat(result).isEmpty();
    }

    private Optional<DocumentGenerationStatusResult> findRecordingProgress() {
        return service.findRecordingProgress(
                WORKSPACE_ID,
                RECORDING_ID,
                NOW
        );
    }

    private void batch(DocumentGenerationProcessingStatus status) {
        DocumentGenerationBatch batch = mock(DocumentGenerationBatch.class);
        when(batch.getId()).thenReturn(BATCH_ID);
        when(batch.getProcessingStatus()).thenReturn(status);
        when(
                batches.findByWorkspaceIdAndRecordingSessionId(
                        WORKSPACE_ID,
                        RECORDING_ID
                )
        ).thenReturn(Optional.of(batch));
    }

    private DocumentGenerationJob failedJob(
            long id,
            long transcriptId
    ) {
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                BATCH_ID,
                transcriptId,
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
