package com.knot.backend.recording.application;

import static org.assertj.core.api.Assertions.*;
import com.knot.backend.document.application.DocumentTranscriptQuery;
import com.knot.backend.recording.application.dto.command.RecordingControlCommand;
import com.knot.backend.recording.application.dto.command.RecordingStartCommand;
import com.knot.backend.recording.application.dto.result.RecordingTranscriptStatus;
import com.knot.backend.recording.application.dto.result.TopicClassificationStatus;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.repository.RecordingMeetingSummaryRepository;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.application.WorkspaceMemberService;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@SpringBootTest
class RecordingDemoIntegrationTest {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    RecordingService recordings;
    @Autowired
    RecordingSpeakerService speakers;
    @Autowired
    RecordingTranscriptQuery transcripts;
    @Autowired
    DocumentTranscriptQuery documentTranscripts;
    @Autowired
    TranscriptSegmentRepository segments;
    @Autowired
    RecordingOutputQuery outputs;
    @Autowired
    RecordingDocumentService detail;
    @Autowired
    RecordingMeetingSummaryRepository summaries;
    @Autowired
    WorkspaceMemberService members;
    RecordingHistoryFixture fixture;
    long workspaceId;
    long owner;
    long colleague;
    Instant now = Instant.now();
    @BeforeEach
    void setup() {
        fixture = new RecordingHistoryFixture(jdbc);
        fixture.clear();
        owner = fixture.member("루덴스");
        colleague = fixture.member("유월");
        workspaceId = fixture.workspace("데모");
        fixture.join(
                workspaceId,
                owner
        );
        fixture.join(
                workspaceId,
                colleague
        );
        jdbc.sql("DELETE FROM recording_meeting_summary_activation")
                .update();
        summaries.recordActivation(now.minusSeconds(3600));
    }
    @Test
    void memberCanRenameOneRecordingAndDocumentSourceWithoutChangingContent() {
        long recording = fixture.savedRecording(
                workspaceId,
                owner,
                now
        );
        long batch = fixture.batch(
                recording,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        long transcript = transcripts.find(recording)
                .orElseThrow()
                .transcriptId();
        segments.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcript,
                                0,
                                0,
                                1000L,
                                1,
                                "안녕"
                        ),
                        TranscriptSegment.create(
                                transcript,
                                1,
                                2000,
                                3000L,
                                1,
                                "계속"
                        ),
                        TranscriptSegment.create(
                                transcript,
                                2,
                                4000,
                                5000L,
                                2,
                                "다른 화자"
                        )
                )
        );
        long document = fixture.document(
                batch,
                "제목",
                "요약",
                now
        );
        String before = transcripts.find(recording)
                .orElseThrow()
                .transcriptText();
        assertThat(
                speakers.rename(
                        workspaceId,
                        colleague,
                        recording,
                        1,
                        "　루덴스 "
                )
        ).isEqualTo("루덴스");
        var result = transcripts.find(recording)
                .orElseThrow();
        assertThat(result.transcriptText()).isEqualTo(before);
        assertThat(result.segments()).extracting(s -> s.speakerDisplayName())
                .containsExactly(
                        "루덴스",
                        "루덴스",
                        null
                );
        assertThat(
                documentTranscripts.find(
                        workspaceId,
                        document
                )
                        .orElseThrow()
                        .segments()
        ).isEqualTo(result.segments());
        assertThatThrownBy(
                () -> speakers.rename(
                        workspaceId,
                        colleague,
                        recording,
                        9,
                        "이름"
                )
        ).isInstanceOf(RecordingException.class);
        assertThatThrownBy(
                () -> speakers.rename(
                        workspaceId,
                        fixture.member("외부"),
                        recording,
                        1,
                        "이름"
                )
        ).isInstanceOf(WorkspaceException.class);
    }
    @Test
    void transcriptAndSummaryAreIndependentOfTopicsAndReadDoesNotEnqueue() {
        long recording = fixture.savedRecording(
                workspaceId,
                owner,
                now
        );
        long batch = fixture.batch(
                recording,
                "WAITING_CLASSIFICATION",
                "QUEUED"
        );
        fixture.failedJob(batch);
        var status = outputs.findAll(
                workspaceId,
                List.of(recording)
        )
                .get(recording);
        assertThat(status.transcriptStatus()).isEqualTo(RecordingTranscriptStatus.READY);
        assertThat(status.topicClassificationStatus()).isEqualTo(TopicClassificationStatus.FAILED);
        assertThat(
                detail.find(
                        workspaceId,
                        colleague,
                        recording
                )
                        .meetingSummary()
                        .status()
                        .name()
        ).isEqualTo("QUEUED");
        assertThat(summaries.findByRecordingId(recording)).isEmpty();
        assertThat(
                summaries.registerEligible(
                        now,
                        10
                )
        ).isEqualTo(1);
        assertThat(summaries.findByRecordingId(recording)).isPresent();
    }
    @Test
    void emptyHasTranscriptButNoSummaryWhileGreetingWithoutTopicsHasSummary() {
        long empty = fixture.savedRecording(
                workspaceId,
                owner,
                now
        );
        fixture.batch(
                empty,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        jdbc.sql("UPDATE transcripts SET content=:content WHERE recording_session_id=:id")
                .param(
                        "content",
                        "{\"transcription\":[]}"
                )
                .param(
                        "id",
                        empty
                )
                .update();
        long greeting = fixture.savedRecording(
                workspaceId,
                owner,
                now.plusSeconds(1)
        );
        fixture.batch(
                greeting,
                "NO_CONTENT",
                "NO_CONTENT"
        );
        jdbc.sql("UPDATE transcripts SET content=:content WHERE recording_session_id=:id")
                .param(
                        "content",
                        "{\"transcription\":[{\"text\":\"안녕하세요\"}]}"
                )
                .param(
                        "id",
                        greeting
                )
                .update();
        assertThat(
                transcripts.find(empty)
                        .orElseThrow()
                        .transcriptText()
        ).isEmpty();
        assertThat(
                outputs.findAll(
                        workspaceId,
                        List.of(empty)
                )
                        .get(empty)
                        .transcriptStatus()
        ).isEqualTo(RecordingTranscriptStatus.EMPTY);
        assertThat(
                detail.find(
                        workspaceId,
                        colleague,
                        empty
                )
                        .meetingSummary()
        ).isNull();
        assertThat(
                summaries.registerEligible(
                        now,
                        1
                )
        ).isEqualTo(1);
        assertThat(summaries.findByRecordingId(empty)).isEmpty();
        assertThat(summaries.findByRecordingId(greeting)).isPresent();
    }
    @Test
    void countPersistsAcrossPauseAndFreezesAtEndAndRejectsOtherController() {
        UUID tab = UUID.randomUUID();
        String token = "A".repeat(43);
        long id = recordings.start(
                workspaceId,
                owner,
                new RecordingStartCommand(
                        UUID.randomUUID(),
                        tab,
                        token
                )
        )
                .recordingId();
        var proof = new RecordingControlCommand(
                tab,
                token
        );
        assertThat(
                recordings.changeSpeakerCount(
                        workspaceId,
                        owner,
                        id,
                        proof,
                        3
                )
        ).isEqualTo(3);
        assertThatThrownBy(
                () -> recordings.changeSpeakerCount(
                        workspaceId,
                        colleague,
                        id,
                        proof,
                        2
                )
        ).isInstanceOf(RecordingException.class);
        recordings.pause(
                workspaceId,
                owner,
                id,
                proof
        );
        assertThat(
                recordings.changeSpeakerCount(
                        workspaceId,
                        owner,
                        id,
                        proof,
                        null
                )
        ).isNull();
        recordings.changeSpeakerCount(
                workspaceId,
                owner,
                id,
                proof,
                2
        );
        recordings.end(
                workspaceId,
                owner,
                id,
                proof
        );
        assertThatThrownBy(
                () -> recordings.changeSpeakerCount(
                        workspaceId,
                        owner,
                        id,
                        proof,
                        4
                )
        ).isInstanceOf(RecordingException.class);
        assertThat(
                jdbc.sql("SELECT speaker_count FROM recording_sessions WHERE id=:id")
                        .param(
                                "id",
                                id
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(2);
    }
    @Test
    void memberPickerIncludesCallerAndPaginatesAndHidesOtherWorkspaces() {
        var first = members.find(
                workspaceId,
                colleague,
                null,
                1
        );
        assertThat(first.items()).hasSize(1);
        var second = members.find(
                workspaceId,
                colleague,
                first.nextCursor(),
                1
        );
        assertThat(
                List.of(
                        first.items()
                                .getFirst()
                                .memberId(),
                        second.items()
                                .getFirst()
                                .memberId()
                )
        ).containsExactly(
                owner,
                colleague
        );
        assertThat(second.nextCursor()).isNull();
        long other = fixture.workspace("다른 곳");
        assertThatThrownBy(
                () -> members.find(
                        other,
                        colleague,
                        null,
                        50
                )
        ).isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(
                () -> members.find(
                        workspaceId,
                        colleague,
                        null,
                        101
                )
        ).isInstanceOf(WorkspaceException.class);
        jdbc.sql("UPDATE workspace_members SET left_at=:now WHERE workspace_id=:id AND member_id=:member")
                .param(
                        "now",
                        Timestamp.from(now)
                )
                .param(
                        "id",
                        workspaceId
                )
                .param(
                        "member",
                        owner
                )
                .update();
        assertThat(
                members.find(
                        workspaceId,
                        colleague,
                        null,
                        50
                )
                        .items()
        ).extracting(i -> i.memberId())
                .containsExactly(colleague);
    }
}
