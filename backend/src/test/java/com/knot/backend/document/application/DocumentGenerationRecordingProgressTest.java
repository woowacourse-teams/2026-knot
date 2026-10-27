package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.Map;
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
    void findRecordingProgresses_success_retryableJobsRegardlessOfHomeExpiry() {
        // given
        DocumentGenerationBatch batch = batch(
                BATCH_ID,
                RECORDING_ID,
                DocumentGenerationProcessingStatus.FAILED
        );
        when(
                batches.findAllByWorkspaceIdAndRecordingSessionIdIn(
                        WORKSPACE_ID,
                        List.of(RECORDING_ID)
                )
        ).thenReturn(List.of(batch));
        DocumentGenerationJob visible = failedJob(
                8L,
                BATCH_ID,
                TRANSCRIPT_ID
        );
        DocumentGenerationJob expired = failedJob(
                10L,
                BATCH_ID,
                TRANSCRIPT_ID
        );
        ReflectionTestUtils.setField(
                expired,
                "expiresAt",
                NOW.minus(Duration.ofDays(30))
        );
        DocumentGenerationJob succeeded = failedJob(
                11L,
                BATCH_ID,
                TRANSCRIPT_ID
        );
        ReflectionTestUtils.setField(
                succeeded,
                "status",
                DocumentGenerationJobStatus.SUCCEEDED
        );
        DocumentGenerationJob unusableInput = failedJob(
                12L,
                BATCH_ID,
                99L
        );
        when(jobs.findAllByBatchIdIn(List.of(BATCH_ID))).thenReturn(
                List.of(
                        expired,
                        visible,
                        succeeded,
                        unusableInput,
                        visible
                )
        );
        when(
                inputs.findAll(
                        eq(WORKSPACE_ID),
                        anyCollection()
                )
        ).thenReturn(
                List.of(
                        input(
                                TRANSCRIPT_ID,
                                RECORDING_ID
                        )
                )
        );

        // when
        DocumentGenerationStatusResult result = findRecordingProgresses(List.of(RECORDING_ID)).get(RECORDING_ID);

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
    void findRecordingProgresses_success_notFailed(DocumentGenerationProcessingStatus status) {
        // given
        DocumentGenerationBatch batch = batch(
                BATCH_ID,
                RECORDING_ID,
                status
        );
        when(
                batches.findAllByWorkspaceIdAndRecordingSessionIdIn(
                        WORKSPACE_ID,
                        List.of(RECORDING_ID)
                )
        ).thenReturn(List.of(batch));

        // when
        DocumentGenerationStatusResult result = findRecordingProgresses(List.of(RECORDING_ID)).get(RECORDING_ID);

        // then
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.retryableJobIds()).isEmpty();
        verifyNoInteractions(
                jobs,
                inputs
        );
    }

    @Test
    @DisplayName("녹음 묶음은 배치·실패 Job·입력 원문을 한 번씩 읽고, 다른 녹음의 원문이나 접수 전 녹음은 결과에 섞지 않는다")
    void findRecordingProgresses_success_manyRecordingsAtOnce() {
        // given
        long otherRecordingId = 5L;
        long notAcceptedRecordingId = 6L;
        long otherBatchId = 7L;
        long otherTranscriptId = 13L;
        List<Long> recordingIds = List.of(
                RECORDING_ID,
                otherRecordingId,
                notAcceptedRecordingId
        );
        DocumentGenerationBatch failed = batch(
                BATCH_ID,
                RECORDING_ID,
                DocumentGenerationProcessingStatus.FAILED
        );
        DocumentGenerationBatch otherFailed = batch(
                otherBatchId,
                otherRecordingId,
                DocumentGenerationProcessingStatus.FAILED
        );
        when(
                batches.findAllByWorkspaceIdAndRecordingSessionIdIn(
                        WORKSPACE_ID,
                        recordingIds
                )
        ).thenReturn(
                List.of(
                        failed,
                        otherFailed
                )
        );
        when(jobs.findAllByBatchIdIn(anyCollection())).thenReturn(
                List.of(
                        failedJob(
                                8L,
                                BATCH_ID,
                                TRANSCRIPT_ID
                        ),
                        failedJob(
                                9L,
                                otherBatchId,
                                otherTranscriptId
                        )
                )
        );
        when(
                inputs.findAll(
                        eq(WORKSPACE_ID),
                        anyCollection()
                )
        ).thenReturn(
                List.of(
                        input(
                                TRANSCRIPT_ID,
                                RECORDING_ID
                        ),
                        input(
                                otherTranscriptId,
                                RECORDING_ID
                        )
                )
        );

        // when
        Map<Long, DocumentGenerationStatusResult> result = findRecordingProgresses(recordingIds);

        // then
        assertThat(result).containsOnlyKeys(
                RECORDING_ID,
                otherRecordingId
        );
        assertThat(
                result.get(RECORDING_ID)
                        .retryableJobIds()
        ).containsExactly(8L);
        assertThat(
                result.get(otherRecordingId)
                        .retryableJobIds()
        ).isEmpty();
        verify(jobs).findAllByBatchIdIn(anyCollection());
        verify(inputs).findAll(
                eq(WORKSPACE_ID),
                anyCollection()
        );
    }

    @Test
    @DisplayName("문서 정리가 접수되지 않은 녹음은 결과가 없다")
    void findRecordingProgresses_success_noBatch() {
        // given
        when(
                batches.findAllByWorkspaceIdAndRecordingSessionIdIn(
                        WORKSPACE_ID,
                        List.of(RECORDING_ID)
                )
        ).thenReturn(List.of());

        // when
        Map<Long, DocumentGenerationStatusResult> result = findRecordingProgresses(List.of(RECORDING_ID));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("녹음이 없으면 저장소를 읽지 않는다")
    void findRecordingProgresses_success_noRecording() {
        // given
        List<Long> recordingIds = List.of();

        // when
        Map<Long, DocumentGenerationStatusResult> result = findRecordingProgresses(recordingIds);

        // then
        assertThat(result).isEmpty();
        verifyNoInteractions(
                batches,
                jobs,
                inputs
        );
    }

    private Map<Long, DocumentGenerationStatusResult> findRecordingProgresses(List<Long> recordingIds) {
        return service.findRecordingProgresses(
                WORKSPACE_ID,
                recordingIds,
                NOW
        );
    }

    private DocumentGenerationBatch batch(
            long batchId,
            long recordingId,
            DocumentGenerationProcessingStatus status
    ) {
        DocumentGenerationBatch batch = mock(DocumentGenerationBatch.class);
        when(batch.getId()).thenReturn(batchId);
        when(batch.getRecordingSessionId()).thenReturn(recordingId);
        when(batch.getProcessingStatus()).thenReturn(status);
        return batch;
    }

    private DocumentGenerationJob failedJob(
            long id,
            long batchId,
            long transcriptId
    ) {
        DocumentGenerationJob job = DocumentGenerationJob.queueGeneration(
                batchId,
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

    private DocumentGenerationInputResult input(
            long transcriptId,
            long recordingId
    ) {
        return new DocumentGenerationInputResult(
                transcriptId,
                recordingId,
                "회의 내용"
        );
    }
}
