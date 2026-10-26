-- #973: 사용자 재시도 3회 제한을 없앤다. user_retry_count는 기록용으로 계속 센다.
ALTER TABLE document_generation_jobs
    DROP CONSTRAINT chk_document_generation_jobs_attempt_counts,
    ADD CONSTRAINT chk_document_generation_jobs_attempt_counts CHECK (
        attempt_count >= 1 AND user_retry_count >= 0
        AND automatic_retry_count >= 0 AND phase_resume_count >= 0
        AND attempt_count::BIGINT = 1::BIGINT + user_retry_count::BIGINT
            + automatic_retry_count::BIGINT + phase_resume_count::BIGINT
    );
