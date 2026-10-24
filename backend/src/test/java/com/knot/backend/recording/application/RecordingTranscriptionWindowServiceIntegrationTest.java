package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.result.TranscriptionWindowClaim;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.repository.RecordingTranscriptionWindowRepository;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class RecordingTranscriptionWindowServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");
    private static final String PIPELINE = "incremental-v1";
    private static final String RESULT = "{\"schemaVersion\":1,\"utterances\":[]}";

    @Autowired
    private RecordingTranscriptionWindowService service;
    @Autowired
    private RecordingTranscriptionWindowRepository windows;
    @Autowired
    private RecordingAudioCleanupStateService cleanup;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @MockitoBean
    private Clock clock;
    @MockitoBean
    private RecordingAudioStorage storage;
    @MockitoBean
    private RecordingAudioFinalizationWorker finalizer;
    @MockitoBean
    private RecordingTranscriptionService transcriptions;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.sql("""
                TRUNCATE TABLE recording_audio_cleanup, recording_sessions, workspace_members,
                    workspaces, oauth_identities, members RESTART IDENTITY CASCADE
                """)
                .update();
        jdbc.sql("INSERT INTO members (nickname) VALUES ('구간')")
                .update();
        jdbc.sql("INSERT INTO workspaces (name, created_at) VALUES ('구간', now())")
                .update();
        rejoin();
        jdbc.sql("""
                INSERT INTO recording_sessions(workspace_id,member_id,request_id,tab_id,
                    control_token_hash,status,audio_mode,started_at,last_seen_at,current_interval_started_at)
                VALUES (1,1,gen_random_uuid(),gen_random_uuid(),repeat('a',64),'RECORDING',
                    'PCM16_CHUNKS_V1',CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ),CAST(:now AS TIMESTAMPTZ))
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        jdbc.sql("INSERT INTO recording_audio_streams(recording_id) VALUES (1)")
                .update();
        addChunks(
                1,
                7
        );
    }

    @Test
    void endedRecordingCanClaimExistingWindowAfterOneYear() {
        assertThat(register()).isEqualTo(1);
        jdbc.sql(
                "UPDATE recording_sessions SET status='ENDED',ended_at=CAST(:at AS TIMESTAMPTZ),current_interval_started_at=NULL,end_reason='USER_ENDED' WHERE id=1"
        )
                .param(
                        "at",
                        NOW.toString()
                )
                .update();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(365)));
        assertThat(claimFirst()).isNotNull();
    }

    @Test
    @DisplayName("활성 PCM 검증 조각을 등록하고 같은 요청은 입력 계약과 시도 ID를 보존한다")
    void registerAvailable_success_idempotent() {
        // given
        assertThat(register()).isEqualTo(1);
        RecordingTranscriptionWindow first = rows().getFirst();

        // when
        int registered = register();

        // then
        assertThat(registered).isZero();
        assertThat(rows()).hasSize(1);
        assertThat(
                rows().getFirst()
                        .getAttemptId()
        ).isEqualTo(first.getAttemptId());
        assertThat(
                rows().getFirst()
                        .getManifestSha256()
        ).isEqualTo(first.getManifestSha256());
        assertNoPublicResults();
    }

    @Test
    @DisplayName("동시 등록은 녹음 잠금으로 직렬화되어 구간 하나만 생성한다")
    void registerAvailable_success_concurrentSingleWindow() throws Exception {
        // given
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = executor.submit(() -> awaitAndRegister(start));
            Future<Integer> second = executor.submit(() -> awaitAndRegister(start));

            // when
            start.countDown();
            int firstCount = first.get(
                    10,
                    TimeUnit.SECONDS
            );
            int secondCount = second.get(
                    10,
                    TimeUnit.SECONDS
            );

            // then
            assertThat(firstCount + secondCount).isEqualTo(1);
            assertThat(rows()).hasSize(1);
        }
    }

    @Test
    @DisplayName("검증되지 않은 조각 뒤의 음성으로 구간을 생성하지 않는다")
    void registerAvailable_success_stopsAtGap() {
        // given
        jdbc.sql("""
                UPDATE recording_audio_chunks SET status='RESERVED',storage_version_id=NULL,verified_at=NULL
                WHERE sequence=3
                """)
                .update();

        // when
        int registered = register();

        // then
        assertThat(registered).isZero();
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("재선점은 시도 ID를 유지하고 이전 소유자 결과를 거절하며 새 결과만 저장한다")
    void claim_success_recoveryAndFencedAdoption() {
        // given
        register();
        TranscriptionWindowClaim stale = claimFirst();
        when(clock.instant()).thenReturn(NOW.plusSeconds(6));
        TranscriptionWindowClaim fresh = claimFirst();
        assertThat(fresh.attemptId()).isEqualTo(stale.attemptId());
        assertThat(fresh.claimEpoch()).isEqualTo(stale.claimEpoch() + 1);
        assertThat(
                service.succeed(
                        stale,
                        1,
                        RESULT
                )
        ).isFalse();

        // when
        boolean adopted = service.succeed(
                fresh,
                1,
                RESULT
        );

        // then
        assertThat(adopted).isTrue();
        assertThat(
                rows().getFirst()
                        .getStatus()
                        .name()
        ).isEqualTo("SUCCEEDED");
        assertThat(
                rows().getFirst()
                        .getResultJson()
        ).isEqualTo(RESULT);
        assertThat(
                rows().getFirst()
                        .getResultSha256()
        ).hasSize(64);
        assertNoPublicResults();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "[]", "null", "{} {}", "{} true", ""})
    @DisplayName("구문 오류 또는 객체가 아닌 결과 JSON을 거절하고 작업을 처리 중으로 보존한다")
    void succeed_failure_invalidJson(String json) {
        // given
        register();
        TranscriptionWindowClaim claim = claimFirst();

        // when
        Throwable thrown = catchThrowable(
                () -> service.succeed(
                        claim,
                        1,
                        json
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class);
        assertThat(
                rows().getFirst()
                        .getStatus()
                        .name()
        ).isEqualTo("PROCESSING");
        assertThat(
                rows().getFirst()
                        .getResultJson()
        ).isNull();
    }

    @Test
    @DisplayName("같은 pipeline 버전에서 입력 구간 설정을 변경하면 기존 입력을 덮어쓰지 않는다")
    void registerAvailable_failure_changedPipelineConfiguration() {
        // given
        register();
        String manifest = rows().getFirst()
                .getManifestSha256();

        // when
        Throwable thrown = catchThrowable(
                () -> service.registerAvailable(
                        1,
                        PIPELINE,
                        320000,
                        80000
                )
        );

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class);
        assertThat(rows()).hasSize(1);
        assertThat(
                rows().getFirst()
                        .getManifestSha256()
        ).isEqualTo(manifest);
    }

    @Test
    @DisplayName("탈퇴 정리는 성공 payload와 실행 소유권을 지우고 재가입 뒤 늦은 결과를 거절한다")
    void afterDeparture_success_purgesAndRejectsLateResult() {
        // given
        addChunks(
                8,
                13
        );
        register();
        TranscriptionWindowClaim completed = claimFirst();
        assertThat(
                service.succeed(
                        completed,
                        1,
                        RESULT
                )
        ).isTrue();
        TranscriptionWindowClaim late = service.claim(
                1,
                rows().get(1)
                        .getId(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        )
                .orElseThrow();

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM workspace_members WHERE workspace_id=1 AND member_id=1")
                    .update();
            cleanup.afterDeparture(
                    1,
                    1
            );
        });

        // then
        assertThat(rows()).hasSize(2)
                .allSatisfy(value -> {
                    assertThat(
                            value.getStatus()
                                    .name()
                    ).isEqualTo("CANCELLED");
                    assertThat(value.getResultJson()).isNull();
                    assertThat(value.getResultSha256()).isNull();
                    assertThat(value.getResultSchemaVersion()).isNull();
                    assertThat(value.getOwnerToken()).isNull();
                    assertThat(value.getLeaseUntil()).isNull();
                });
        assertThat(
                jdbc.sql("SELECT scope FROM recording_audio_cleanup WHERE recording_id=1")
                        .query(String.class)
                        .single()
        ).isEqualTo("ALL");
        assertThat(
                jdbc.sql("SELECT frozen FROM recording_audio_streams WHERE recording_id=1")
                        .query(Boolean.class)
                        .single()
        ).isTrue();
        rejoin();
        assertThat(
                service.succeed(
                        late,
                        1,
                        RESULT
                )
        ).isFalse();
        assertThat(register()).isZero();
        assertNoPublicResults();
    }

    @Test
    @DisplayName("종료된 녹음의 이미 등록된 작업은 실행할 수 있고 동결된 stream은 새 실행을 막는다")
    void claim_success_endedButNotFrozen() {
        // given
        register();
        jdbc.sql("""
                UPDATE recording_sessions SET status='ENDED',ended_at=CAST(:now AS TIMESTAMPTZ),
                    end_reason='USER_ENDED',current_interval_started_at=NULL
                WHERE id=1
                """)
                .param(
                        "now",
                        NOW.toString()
                )
                .update();
        TranscriptionWindowClaim first = claimFirst();
        assertThat(
                service.defer(
                        first,
                        NOW,
                        "RETRY"
                )
        ).isTrue();
        jdbc.sql("UPDATE recording_audio_streams SET frozen=TRUE WHERE recording_id=1")
                .update();

        // when
        java.util.Optional<TranscriptionWindowClaim> claim = service.claim(
                1,
                first.windowId(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );

        // then
        assertThat(claim).isEmpty();
        assertNoPublicResults();
    }

    @Test
    @DisplayName("실행 기한 만료 작업은 복구 조회 후 실패로 종료되고 오래된 결과를 거절한다")
    void expire_success_deadlineRecovery() {
        // given
        register();
        TranscriptionWindowClaim claim = claimFirst();
        when(clock.instant()).thenReturn(NOW.plusSeconds(299));
        assertThat(
                windows.findExpiredIds(
                        clock.instant(),
                        10
                )
        ).isEmpty();
        assertThat(
                service.expire(
                        1,
                        claim.windowId()
                )
        ).isFalse();
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        assertThat(
                windows.findExpiredIds(
                        clock.instant(),
                        10
                )
        ).containsExactly(claim.windowId());

        // when
        boolean expired = service.expire(
                1,
                claim.windowId()
        );

        // then
        assertThat(expired).isTrue();
        assertThat(
                rows().getFirst()
                        .getStatus()
                        .name()
        ).isEqualTo("FAILED");
        assertThat(
                rows().getFirst()
                        .getFailureCode()
        ).isEqualTo("EXECUTION_DEADLINE_EXCEEDED");
        assertThat(
                rows().getFirst()
                        .getOwnerToken()
        ).isNull();
        assertThat(
                windows.findExpiredIds(
                        clock.instant(),
                        10
                )
        ).isEmpty();
        assertThat(
                service.succeed(
                        claim,
                        1,
                        RESULT
                )
        ).isFalse();
        assertNoPublicResults();
    }

    private int register() {
        return service.registerAvailable(
                1,
                PIPELINE,
                480000,
                80000
        );
    }

    private TranscriptionWindowClaim claimFirst() {
        return service.claim(
                1,
                rows().getFirst()
                        .getId(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        )
                .orElseThrow();
    }

    private List<RecordingTranscriptionWindow> rows() {
        return windows.findByRecordingIdAndPipelineVersionOrderByWindowIndex(
                1,
                PIPELINE
        );
    }

    private void addChunks(
            int first,
            int last
    ) {
        for (int sequence = first; sequence <= last; sequence++) {
            jdbc.sql("""
                    INSERT INTO recording_audio_chunks(recording_id,sequence,sample_offset,frame_count,
                        byte_length,checksum_sha256,storage_key,status,storage_version_id,
                        verified_at,upload_url_expires_at)
                    VALUES (1,:sequence,:offset,80000,160000,'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=',
                        :key,'VERIFIED',:version,CAST(:now AS TIMESTAMPTZ),
                        CAST(:now AS TIMESTAMPTZ)+INTERVAL '300 seconds')
                    """)
                    .param(
                            "sequence",
                            sequence
                    )
                    .param(
                            "offset",
                            (sequence - 1L) * 80000
                    )
                    .param(
                            "key",
                            "recordings/1/chunks/" + sequence
                    )
                    .param(
                            "version",
                            "version-" + sequence
                    )
                    .param(
                            "now",
                            NOW.toString()
                    )
                    .update();
        }
        jdbc.sql("""
                UPDATE recording_audio_streams SET next_sequence=:sequence,total_frame_count=:frames
                WHERE recording_id=1
                """)
                .param(
                        "sequence",
                        last + 1
                )
                .param(
                        "frames",
                        last * 80000L
                )
                .update();
    }

    private void rejoin() {
        jdbc.sql("""
                INSERT INTO workspace_members(workspace_id,member_id,role,joined_at,last_viewed)
                VALUES (1,1,'OWNER',now(),FALSE)
                """)
                .update();
    }

    private void assertNoPublicResults() {
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM recording_transcriptions")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM transcripts")
                        .query(Long.class)
                        .single()
        ).isZero();
        assertThat(
                jdbc.sql("SELECT COUNT(*) FROM document_generation_batches")
                        .query(Long.class)
                        .single()
        ).isZero();
    }

    private int awaitAndRegister(CountDownLatch start) {
        try {
            if (!start.await(
                    5,
                    TimeUnit.SECONDS
            )) {
                throw new IllegalStateException("동시 등록 대기 시간이 초과되었습니다");
            }
            return register();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(
                    "동시 등록이 중단되었습니다",
                    exception
            );
        }
    }
}
