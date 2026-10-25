-- V61 구버전이 사용하는 컬럼만으로 생성·재시도·완료·문서 보관을 수행한다.
DO $$
DECLARE
    member_key BIGINT;
    workspace_key BIGINT;
    recording_key BIGINT;
    transcript_key BIGINT;
    batch_key BIGINT;
    job_key BIGINT;
    document_key BIGINT;
    stamp TIMESTAMPTZ := now();
BEGIN
    INSERT INTO members(nickname) VALUES ('migration-proof') RETURNING id INTO member_key;
    INSERT INTO workspaces(name,created_at) VALUES ('migration-proof',stamp) RETURNING id INTO workspace_key;
    INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,control_token_hash,
        status,started_at,ended_at,last_seen_at)
    VALUES(workspace_key,member_key,gen_random_uuid(),gen_random_uuid(),repeat('a',64),
        'ENDED',stamp,stamp,stamp) RETURNING id INTO recording_key;
    INSERT INTO transcripts(recording_session_id,content,created_at)
        VALUES(recording_key,'proof',stamp) RETURNING id INTO transcript_key;
    INSERT INTO document_generation_batches(recording_session_id,transcript_id,
        topic_registration_state,accepted_at,registered_at,processing_status,queued_count)
    VALUES(recording_key,transcript_key,'TOPICS_REGISTERED',stamp,stamp,'QUEUED',1)
        RETURNING id INTO batch_key;
    INSERT INTO document_generation_batch_topics(batch_id,position,topic) VALUES(batch_key,0,'proof');
    INSERT INTO document_generation_jobs(transcript_id,status,created_at,updated_at,
        batch_id,stage,topic,next_attempt_at,attempt_count,user_retry_count,automatic_retry_count)
    VALUES(transcript_key,'QUEUED',stamp,stamp,batch_key,'GENERATION','proof',stamp,1,0,0)
        RETURNING id INTO job_key;
    UPDATE document_generation_jobs SET status='RUNNING',next_attempt_at=NULL,
        execution_deadline_at=stamp+interval '150 seconds' WHERE id=job_key;
    UPDATE document_generation_jobs SET status='FAILED',execution_deadline_at=NULL,
        failure_cause='TIMEOUT',last_failed_at=stamp,expires_at=stamp+interval '7 days' WHERE id=job_key;
    UPDATE document_generation_jobs SET status='QUEUED',failure_cause=NULL,
        last_failed_at=NULL,expires_at=NULL,next_attempt_at=stamp,
        attempt_count=2,user_retry_count=1 WHERE id=job_key;
    UPDATE document_generation_jobs SET status='SUCCEEDED',next_attempt_at=NULL WHERE id=job_key;
    INSERT INTO documents(workspace_id,recording_session_id,source_transcript_id,
        document_generation_job_id,topic,title,content,status,created_at)
    VALUES(workspace_key,recording_key,transcript_key,job_key,'proof','proof','proof','DRAFT',stamp)
        RETURNING id INTO document_key;
    UPDATE documents SET status='ARCHIVED',archived_at=stamp WHERE id=document_key;
    IF (SELECT status FROM documents WHERE id=document_key) <> 'ARCHIVED' THEN
        RAISE EXCEPTION 'Legacy archive failed';
    END IF;
    IF (SELECT attempt_count FROM document_generation_jobs WHERE id=job_key) <> 2 THEN
        RAISE EXCEPTION 'Legacy retry failed';
    END IF;
END $$;
