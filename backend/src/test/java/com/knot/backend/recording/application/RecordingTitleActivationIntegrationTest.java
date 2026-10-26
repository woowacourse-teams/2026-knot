package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecordingTitleActivationIntegrationTest {
    private static final Instant ACTIVATED_AT = Instant.parse("2026-10-25T10:00:00Z");
    private static final long RECORDING_SECONDS = 120;

    private final RecordingTitleGenerationService recordingTitleGenerationService;
    private final RecentRecordingListService recentRecordingListService;
    private final JdbcClient jdbcClient;
    private final RecordingHistoryFixture fixture;
    @MockitoBean
    private Clock clock;
    private long workspaceId;
    private long memberId;

    RecordingTitleActivationIntegrationTest(
            RecordingTitleGenerationService recordingTitleGenerationService,
            RecentRecordingListService recentRecordingListService,
            JdbcClient jdbcClient
    ) {
        this.recordingTitleGenerationService = recordingTitleGenerationService;
        this.recentRecordingListService = recentRecordingListService;
        this.jdbcClient = jdbcClient;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(ACTIVATED_AT);
        fixture.clear();
        memberId = fixture.member("루덴스");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("기능을 켠 시각보다 먼저 끝난 녹음은 접수하지 않고 그 시각 이후에 끝난 녹음부터 접수한다")
    void registerEligible_success_onlyEndedSinceActivation() {
        // given
        recordingEndedAt(ACTIVATED_AT.minusNanos(1_000));
        long atBoundary = recordingEndedAt(ACTIVATED_AT);
        long afterActivation = recordingEndedAt(ACTIVATED_AT.plusSeconds(1));

        // when
        recordingTitleGenerationService.registerEligible();

        // then
        assertThat(registeredRecordingIds()).containsExactly(
                atBoundary,
                afterActivation
        );
        assertThat(activatedAts()).containsExactly(ACTIVATED_AT);
    }

    @Test
    @DisplayName("다시 실행하거나 껐다 켜도 처음 기록한 시각을 유지하고 그 사이 끝난 녹음도 접수한다")
    void registerEligible_success_keepsFirstActivation() {
        // given
        recordingTitleGenerationService.registerEligible();
        long endedWhileStopped = recordingEndedAt(ACTIVATED_AT.plusSeconds(1800));
        when(clock.instant()).thenReturn(ACTIVATED_AT.plusSeconds(3600));

        // when
        recordingTitleGenerationService.registerEligible();

        // then
        assertThat(activatedAts()).containsExactly(ACTIVATED_AT);
        assertThat(registeredRecordingIds()).containsExactly(endedWhileStopped);
    }

    @Test
    @DisplayName("여러 서버가 동시에 처음 실행해도 활성화 시각은 하나만 남는다")
    void registerEligible_success_concurrentFirstRun() {
        // given
        int runners = 6;
        when(clock.instant()).thenReturn(
                ACTIVATED_AT,
                ACTIVATED_AT.plusSeconds(1),
                ACTIVATED_AT.plusSeconds(2),
                ACTIVATED_AT.plusSeconds(3),
                ACTIVATED_AT.plusSeconds(4),
                ACTIVATED_AT.plusSeconds(5)
        );
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (int index = 0; index < runners; index++) {
            runs.add(CompletableFuture.runAsync(() -> {
                await(start);
                recordingTitleGenerationService.registerEligible();
            }));
        }

        // when
        start.countDown();
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new))
                .join();

        // then
        assertThat(activatedAts()).hasSize(1)
                .allSatisfy(
                        activatedAt -> assertThat(activatedAt).isBetween(
                                ACTIVATED_AT,
                                ACTIVATED_AT.plusSeconds(5)
                        )
                );
    }

    @Test
    @DisplayName("기능을 켜기 전에 끝난 녹음은 제목을 만들지 않고 대체 이름으로 남는다")
    void runOnce_success_pastRecordingKeepsFallback() {
        // given
        long pastRecording = recordingEndedAt(ACTIVATED_AT.minusSeconds(60));
        AtomicInteger generatorCalls = new AtomicInteger();
        RecordingTitleWorker worker = new RecordingTitleWorker(
                recordingTitleGenerationService,
                transcript -> {
                    generatorCalls.incrementAndGet();
                    return "회원 탈퇴 정책 논의";
                }
        );

        // when
        worker.runOnce();
        worker.shutdown();

        // then
        assertThat(generatorCalls).hasValue(0);
        assertThat(registeredRecordingIds()).isEmpty();
        assertThat(
                recentRecordingListService.find(
                        workspaceId,
                        memberId,
                        RecentRecordingListParameters.of(
                                null,
                                null
                        )
                )
                        .items()
        ).extracting(
                RecentRecordingItemResult::recordingId,
                RecentRecordingItemResult::title
        )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                pastRecording,
                                "루덴스 님의 녹음"
                        )
                );
    }

    private long recordingEndedAt(Instant endedAt) {
        long recordingId = fixture.savedRecording(
                workspaceId,
                memberId,
                endedAt.minusSeconds(RECORDING_SECONDS)
        );
        fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        return recordingId;
    }

    private List<Long> registeredRecordingIds() {
        return jdbcClient.sql("SELECT recording_id FROM recording_title_generations ORDER BY recording_id")
                .query(Long.class)
                .list();
    }

    private List<Instant> activatedAts() {
        return jdbcClient.sql("SELECT activated_at FROM recording_title_activation")
                .query(
                        (
                                resultSet,
                                rowNumber
                        ) -> resultSet.getTimestamp("activated_at")
                                .toInstant()
                )
                .list();
    }

    private void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
