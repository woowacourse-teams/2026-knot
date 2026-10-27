package com.knot.backend.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.document.application.dto.result.DocumentRecordingItemResult;
import com.knot.backend.document.application.dto.result.DocumentRecordingListResult;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.testsupport.RecordingHistoryFixture;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import com.knot.backend.workspace.domain.WorkspaceErrorCode;
import com.knot.backend.workspace.domain.WorkspaceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class DocumentRecordingListServiceIntegrationTest {
    private static final Instant STARTED_AT = Instant.parse("2026-10-24T09:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-10-24T10:00:00Z");
    private static final int PAGE_SIZE = 20;

    private final DocumentRecordingListService documentRecordingListService;
    private final RecordingHistoryFixture fixture;
    private long workspaceId;
    private long memberId;
    private long teammateId;

    DocumentRecordingListServiceIntegrationTest(
            DocumentRecordingListService documentRecordingListService,
            JdbcClient jdbcClient
    ) {
        this.documentRecordingListService = documentRecordingListService;
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
    @DisplayName("녹음 20개씩 나눠 주고 경계의 녹음도 문서를 나누지 않으며 마지막 페이지의 다음 커서는 없다")
    void find_success_pagesTwentyRecordings() {
        // given
        List<Long> firstPageIds = new ArrayList<>();
        for (int secondsAfterStart = PAGE_SIZE; secondsAfterStart > 1; secondsAfterStart--) {
            firstPageIds.add(
                    recordingWithDocuments(
                            teammateId,
                            secondsAfterStart,
                            1
                    )
            );
        }
        firstPageIds.add(
                recordingWithDocuments(
                        teammateId,
                        1,
                        2
                )
        );
        long last = recordingWithDocuments(
                teammateId,
                0,
                1
        );

        // when
        DocumentRecordingListResult firstPage = find(null);
        DocumentRecordingListResult nextPage = find(firstPage.nextCursor());

        // then
        assertThat(recordingIds(firstPage)).containsExactlyElementsOf(firstPageIds);
        assertThat(
                firstPage.items()
                        .getLast()
                        .documents()
        ).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(recordingIds(nextPage)).containsExactly(last);
        assertThat(nextPage.nextCursor()).isNull();
    }

    @Test
    @DisplayName("문서가 나온 녹음이 없으면 빈 목록과 null 커서를 준다")
    void find_success_empty() {
        // given
        fixture.savedRecording(
                workspaceId,
                memberId,
                STARTED_AT
        );

        // when
        DocumentRecordingListResult result = find(null);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("현재 Workspace 멤버가 아니면 녹음별 문서 목록을 주지 않는다")
    void find_fail_notMember() {
        // given
        long outsiderId = fixture.member("외부인");
        recordingWithDocuments(
                memberId,
                0,
                1
        );

        // when & then
        assertThatThrownBy(
                () -> documentRecordingListService.find(
                        workspaceId,
                        outsiderId,
                        null
                )
        ).isInstanceOf(WorkspaceException.class)
                .extracting("errorCode")
                .isEqualTo(WorkspaceErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-cursor"})
    @DisplayName("빈 문자열이거나 해석할 수 없는 cursor는 거절한다")
    void find_fail_invalidCursor(String cursor) {
        // when & then
        assertThatThrownBy(() -> find(cursor)).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("다른 Workspace에서 받은 cursor는 거절한다")
    void find_fail_otherWorkspaceCursor() {
        // given
        long otherWorkspaceId = fixture.workspace("다른 팀");
        fixture.join(
                otherWorkspaceId,
                memberId
        );
        for (int index = 0; index <= PAGE_SIZE; index++) {
            long recordingId = fixture.savedRecording(
                    otherWorkspaceId,
                    memberId,
                    STARTED_AT.plusSeconds(index)
            );
            document(
                    fixture.batch(
                            recordingId,
                            "TOPICS_REGISTERED",
                            "SUCCEEDED"
                    )
            );
        }
        String otherCursor = documentRecordingListService.find(
                otherWorkspaceId,
                memberId,
                null
        )
                .nextCursor();

        // when & then
        assertThatThrownBy(() -> find(otherCursor)).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_PARAMETER);
    }

    private long recordingWithDocuments(
            long starterId,
            int secondsAfterStart,
            int documentCount
    ) {
        long recordingId = fixture.savedRecording(
                workspaceId,
                starterId,
                STARTED_AT.plusSeconds(secondsAfterStart)
        );
        long batchId = fixture.batch(
                recordingId,
                "TOPICS_REGISTERED",
                "SUCCEEDED"
        );
        for (int index = 0; index < documentCount; index++) {
            document(batchId);
        }
        return recordingId;
    }

    private void document(long batchId) {
        fixture.document(
                batchId,
                "회원 탈퇴 정책",
                "탈퇴 정책을 정했다.",
                CREATED_AT
        );
    }

    private DocumentRecordingListResult find(String cursor) {
        return documentRecordingListService.find(
                workspaceId,
                memberId,
                cursor
        );
    }

    private List<Long> recordingIds(DocumentRecordingListResult result) {
        return result.items()
                .stream()
                .map(DocumentRecordingItemResult::recordingId)
                .toList();
    }
}
