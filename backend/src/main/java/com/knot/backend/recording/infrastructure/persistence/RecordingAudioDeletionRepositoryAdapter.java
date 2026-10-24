package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.recording.domain.repository.RecordingAudioDeletionRepository;
import com.knot.backend.recording.domain.model.RecordingAudioDeletionTask;
import com.knot.backend.recording.domain.model.RecordingAudioUpload;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class RecordingAudioDeletionRepositoryAdapter implements RecordingAudioDeletionRepository {

    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<Long> findNoContentCandidates(int limit) {
        return entityManager.createQuery(
                """
                        SELECT u.id FROM RecordingAudioUpload u
                        WHERE u.status = com.knot.backend.recording.domain.model.RecordingAudioUploadStatus.COMPLETED
                            AND u.deletedAt IS NULL AND NOT EXISTS (
                                SELECT d.id FROM RecordingAudioDeletionTask d WHERE d.uploadId = u.id)
                            AND EXISTS (
                                SELECT b.id FROM DocumentGenerationBatch b WHERE b.recordingSessionId = u.recordingId
                                    AND b.processingStatus =
                                        com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus.NO_CONTENT)
                        ORDER BY u.completedAt, u.id
                        """,
                Long.class
        )
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> findReadyTasks(
            Instant now,
            int limit
    ) {
        return entityManager
                .createQuery(
                        """
                                SELECT t.id FROM RecordingAudioDeletionTask t WHERE
                                    EXISTS (SELECT b.id FROM DocumentGenerationBatch b, RecordingAudioUpload u
                                        WHERE u.id = t.uploadId AND b.recordingSessionId = u.recordingId
                                            AND b.processingStatus = com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus.NO_CONTENT)
                                    AND ((t.status = com.knot.backend.recording.domain.model.RecordingAudioDeletionStatus.PENDING AND t.nextAttemptAt <= :now)
                                    OR (t.status = com.knot.backend.recording.domain.model.RecordingAudioDeletionStatus.RUNNING AND t.executionDeadlineAt <= :now))
                                ORDER BY t.id
                                """,
                        Long.class
                )
                .setParameter(
                        "now",
                        now
                )
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public Optional<RecordingAudioUpload> findUploadForUpdate(long uploadId) {
        limitLockWait();
        return Optional.ofNullable(
                entityManager.find(
                        RecordingAudioUpload.class,
                        uploadId,
                        LockModeType.PESSIMISTIC_WRITE
                )
        );
    }

    @Override
    public Optional<RecordingAudioUpload> findUploadByRecordingForUpdate(long recordingId) {
        limitLockWait();
        return entityManager.createQuery(
                "SELECT u FROM RecordingAudioUpload u WHERE u.recordingId = :id",
                RecordingAudioUpload.class
        )
                .setParameter(
                        "id",
                        recordingId
                )
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecordingAudioDeletionTask> findTask(long taskId) {
        return Optional.ofNullable(
                entityManager.find(
                        RecordingAudioDeletionTask.class,
                        taskId
                )
        );
    }

    @Override
    public Optional<RecordingAudioDeletionTask> findTaskForUpdate(long taskId) {
        RecordingAudioDeletionTask task = entityManager.find(
                RecordingAudioDeletionTask.class,
                taskId
        );
        if (task == null) {
            return Optional.empty();
        }
        // upload 잠금을 기다리는 동안 다른 실행기가 갱신했을 수 있어 1차 캐시 대신 현재 행을 읽는다.
        entityManager.refresh(
                task,
                LockModeType.PESSIMISTIC_WRITE
        );
        return Optional.of(task);
    }

    @Override
    public boolean hasTaskForUpload(long uploadId) {
        return entityManager.createQuery(
                "SELECT count(t) FROM RecordingAudioDeletionTask t WHERE t.uploadId = :id",
                Long.class
        )
                .setParameter(
                        "id",
                        uploadId
                )
                .getSingleResult() > 0;
    }

    @Override
    public boolean hasNoContentResult(long recordingId) {
        return entityManager
                .createQuery(
                        """
                                SELECT count(b) FROM DocumentGenerationBatch b WHERE b.recordingSessionId = :id
                                    AND b.processingStatus = com.knot.backend.document.domain.model.DocumentGenerationProcessingStatus.NO_CONTENT
                                """,
                        Long.class
                )
                .setParameter(
                        "id",
                        recordingId
                )
                .getSingleResult() > 0;
    }

    @Override
    public void saveAndFlush(RecordingAudioDeletionTask task) {
        entityManager.persist(task);
        entityManager.flush();
    }

    private void limitLockWait() {
        entityManager.createNativeQuery(
                "SELECT set_config('lock_timeout', '3s', true)",
                String.class
        )
                .getSingleResult();
    }
}
