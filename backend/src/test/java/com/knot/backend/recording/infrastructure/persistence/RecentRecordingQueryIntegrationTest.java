package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.recording.application.RecentRecordingQuery;
import com.knot.backend.recording.application.dto.result.RecentRecordingItemResult;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class RecentRecordingQueryIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");

    private final RecentRecordingQuery recentRecordingQuery;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    RecentRecordingQueryIntegrationTest(
            RecentRecordingQuery recentRecordingQuery,
            JdbcClient jdbcClient
    ) {
        this.recentRecordingQuery = recentRecordingQuery;
        this.fixture = new RecordingHistoryFixture(jdbcClient);
    }

    @BeforeEach
    void setUp() {
        fixture.clear();
        memberId = fixture.member("루덴스");
        teammateId = fixture.member("흑곰");
        workspaceId = fixture.workspace("녹음 팀");
        fixture.join(
                workspaceId,
                memberId
        );
        fixture.join(
                workspaceId,
                teammateId
        );
    }

    @Test
    @DisplayName("녹음 문서를 열 수 있는 녹음은 최근 녹음 목록에 담기는 녹음과 같다")
    void contains_success_sameAsRecentRecordings() {
        // given
        long processing = saved(
                teammateId,
                0
        );
        long transcriptionFailed = saved(
                memberId,
                1
        );
        fixture.transcriptionFailed(transcriptionFailed);
        long noContent = saved(
                memberId,
                2
        );
        fixture.batch(
                noContent,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        long finalizationPending = pcm(3);
        long finalized = pcm(4);
        fixture.finalization(
                finalized,
                "READY"
        );
        long recording = saved(
                memberId,
                5
        );
        fixture.changeStatus(
                recording,
                "RECORDING"
        );
        long paused = saved(
                memberId,
                6
        );
        fixture.changeStatus(
                paused,
                "PAUSED"
        );
        long discarded = saved(
                memberId,
                7
        );
        fixture.changeStatus(
                discarded,
                "DISCARDED"
        );
        long saveFailed = pcm(8);
        fixture.finalization(
                saveFailed,
                "FAILED"
        );
        long notUploaded = fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(9),
                "FINAL_WEBM_V1"
        );
        List<Long> recordingIds = List.of(
                processing,
                transcriptionFailed,
                noContent,
                finalizationPending,
                finalized,
                recording,
                paused,
                discarded,
                saveFailed,
                notUploaded
        );

        // when
        List<Long> openable = recordingIds.stream()
                .filter(
                        recordingId -> recentRecordingQuery.contains(
                                workspaceId,
                                recordingId
                        )
                )
                .toList();

        // then
        assertThat(openable).containsExactlyInAnyOrderElementsOf(listedRecordingIds())
                .containsExactlyInAnyOrder(
                        processing,
                        transcriptionFailed,
                        noContent,
                        finalizationPending,
                        finalized
                );
    }

    @Test
    @DisplayName("다른 Workspace의 녹음과 없는 녹음은 열 수 없다")
    void contains_fail_otherWorkspaceOrMissing() {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        long otherWorkspaceRecording = fixture.savedRecording(
                otherWorkspaceId,
                memberId,
                STARTED_AT
        );

        // when
        boolean otherWorkspace = recentRecordingQuery.contains(
                workspaceId,
                otherWorkspaceRecording
        );
        boolean missing = recentRecordingQuery.contains(
                workspaceId,
                Long.MAX_VALUE
        );

        // then
        assertThat(otherWorkspace).isFalse();
        assertThat(missing).isFalse();
    }

    private long saved(
            long starterId,
            int secondsAfterStart
    ) {
        return fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT.plusSeconds(secondsAfterStart)
        );
    }

    private long pcm(int secondsAfterStart) {
        return fixture.endedRecording(
                workspaceId,
                memberId,
                STARTED_AT.plusSeconds(secondsAfterStart),
                "PCM16_CHUNKS_V1"
        );
    }

    private List<Long> listedRecordingIds() {
        return recentRecordingQuery.findPage(
                workspaceId,
                null,
                100
        )
                .stream()
                .map(RecentRecordingItemResult::recordingId)
                .toList();
    }
}
