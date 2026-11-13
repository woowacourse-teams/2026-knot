package com.knot.backend.recording.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.recording.domain.model.TranscriptSegment;
import com.knot.backend.recording.domain.model.TranscriptUtterance;
import com.knot.backend.recording.domain.repository.TranscriptSegmentRepository;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@DataJpaTest
@IsolatedPostgresTest
@Import({TranscriptSegmentRepositoryAdapter.class})
class TranscriptSegmentRepositoryIntegrationTest {
    @Autowired
    private TranscriptSegmentRepository transcriptSegmentRepository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private EntityManager entityManager;
    private DocumentFixtures fixtures;
    private long recordingId;
    private long transcriptId;

    @BeforeEach
    void setUp() {
        fixtures = new DocumentFixtures(jdbc);
        long workspaceId = fixtures.saveWorkspace();
        long memberId = fixtures.saveMember("녹음자");
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                10000
        );
        transcriptId = fixtures.saveTranscript(recordingId);
    }

    @Test
    @DisplayName("구간을 저장하면 DB 시각으로 생성 시간을 채우고 다시 조회해도 유지한다")
    void saveAll_success_recordsDatabaseCreationTime() {
        // given
        Instant databaseNow = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        TranscriptSegment segment = TranscriptSegment.create(
                transcriptId,
                0,
                0,
                null,
                null,
                "발언"
        );

        // when
        transcriptSegmentRepository.saveAll(List.of(segment));

        // then
        entityManager.flush();
        assertThat(segment.getCreatedAt()).isEqualTo(databaseNow);
        entityManager.clear();
        assertThat(transcriptSegmentRepository.findAllByTranscriptId(transcriptId)).singleElement()
                .extracting(TranscriptSegment::getCreatedAt)
                .isEqualTo(databaseNow);
    }

    @Test
    @DisplayName("SQL로 구간을 추가해도 생성 시간이 기본값으로 기록된다")
    void insert_success_recordsDatabaseCreationTime() {
        // given
        Instant databaseNow = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query(OffsetDateTime.class)
                .single()
                .toInstant();

        // when
        jdbc.sql("""
                INSERT INTO transcript_segments (transcript_id, position, start_millis, text)
                VALUES (:id, 0, 0, '발언')
                """)
                .param(
                        "id",
                        transcriptId
                )
                .update();

        // then
        assertThat(transcriptSegmentRepository.findAllByTranscriptId(transcriptId)).singleElement()
                .extracting(TranscriptSegment::getCreatedAt)
                .isEqualTo(databaseNow);
    }

    @Test
    @DisplayName("저장한 구간을 원문별 시작 시각·동률 순서로 모두 읽는다")
    void findAllByTranscriptId_success_orderAndScope() {
        // given
        long otherTranscript = fixtures.saveTranscript(recordingId);
        transcriptSegmentRepository.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcriptId,
                                2,
                                1200,
                                1600L,
                                2,
                                "세 번째"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                1,
                                1200,
                                null,
                                null,
                                "두 번째"
                        ),
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                100L,
                                1,
                                "첫 번째"
                        ),
                        TranscriptSegment.create(
                                otherTranscript,
                                0,
                                0,
                                null,
                                null,
                                "다른 원문"
                        )
                )
        );
        entityManager.flush();
        entityManager.clear();

        // when
        List<TranscriptSegment> result = transcriptSegmentRepository.findAllByTranscriptId(transcriptId);

        // then
        assertThat(result).extracting(TranscriptSegment::getText)
                .containsExactly(
                        "첫 번째",
                        "두 번째",
                        "세 번째"
                );
        assertThat(
                result.get(1)
                        .getEndMillis()
        ).isNull();
        assertThat(
                result.get(1)
                        .getSpeakerNumber()
        ).isNull();
        assertThat(
                result.get(2)
                        .getEndMillis()
        ).isEqualTo(1600);
        assertThat(
                result.get(2)
                        .getSpeakerNumber()
        ).isEqualTo(2);
        assertThat(result).extracting(TranscriptSegment::getId)
                .doesNotContainNull();
    }

    @Test
    @DisplayName("없는 원문의 구간 조회는 빈 목록이다")
    void findAllByTranscriptId_success_absent() {
        // given
        long missingTranscriptId = Long.MAX_VALUE;

        // when
        List<TranscriptSegment> segments = transcriptSegmentRepository.findAllByTranscriptId(missingTranscriptId);

        // then
        assertThat(segments).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {":id, -1, 0, NULL, NULL, '발언'", ":id, 0, -1, NULL, NULL, '발언'",
            ":id, 0, 100, 99, NULL, '발언'", ":id, 0, 0, NULL, 0, '발언'", ":id, 0, 0, NULL, -1, '발언'",
            ":id, 0, NULL, NULL, NULL, '발언'", ":id, NULL, 0, NULL, NULL, '발언'", "NULL, 0, 0, NULL, NULL, '발언'",
            ":id, 0, 0, NULL, NULL, NULL", ":id, 0, 0, NULL, NULL, ''", ":id, 0, 0, NULL, NULL, E' \\t\\n'",
            ":id, 0, 0, NULL, NULL, U&'\\2003'"})
    @DisplayName("Entity를 우회한 잘못된 구간도 DB가 거절한다")
    void saveAll_failure_databaseConstraints(String values) {
        // given
        String statement = "INSERT INTO transcript_segments "
                + "(transcript_id, position, start_millis, end_millis, speaker_number, text) VALUES (" + values + ")";

        // when
        Throwable failure = catchThrowable(
                () -> jdbc.sql(statement)
                        .param(
                                "id",
                                transcriptId
                        )
                        .update()
        );

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 원문의 중복 저장 순서를 DB가 거절한다")
    void saveAll_failure_duplicatePosition() {
        // given
        transcriptSegmentRepository.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                null,
                                null,
                                "발언"
                        )
                )
        );
        entityManager.flush();

        // when
        Throwable failure = catchThrowable(
                () -> jdbc
                        .sql(
                                "INSERT INTO transcript_segments (transcript_id, position, start_millis, text) "
                                        + "VALUES (:id, 0, 100, '중복')"
                        )
                        .param(
                                "id",
                                transcriptId
                        )
                        .update()
        );

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("없는 원문을 참조하는 구간을 DB가 거절한다")
    void saveAll_failure_missingTranscript() {
        // given
        TranscriptSegment segment = TranscriptSegment.create(
                Long.MAX_VALUE,
                0,
                0,
                null,
                null,
                "발언"
        );

        // when
        Throwable failure = catchThrowable(() -> transcriptSegmentRepository.saveAll(List.of(segment)));

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("구간이 남아 있는 원문의 삭제를 RESTRICT로 막는다")
    void deleteTranscript_failure_restricted() {
        // given
        transcriptSegmentRepository.saveAll(
                List.of(
                        TranscriptSegment.create(
                                transcriptId,
                                0,
                                0,
                                null,
                                null,
                                "발언"
                        )
                )
        );
        entityManager.flush();

        // when
        Throwable failure = catchThrowable(
                () -> jdbc.sql("DELETE FROM transcripts WHERE id = :id")
                        .param(
                                "id",
                                transcriptId
                        )
                        .update()
        );

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("구간 전사의 미확정 화자는 DB에서도 null 번호와 원문으로 보존한다")
    void saveAll_success_preservesUnknownSpeakerAsNull() {
        // given
        List<TranscriptSegment> segments = TranscriptSegment.createAll(
                transcriptId,
                List.of(
                        new TranscriptUtterance(
                                "UNKNOWN_0_local",
                                0,
                                1,
                                "누군가 말한 내용"
                        ),
                        new TranscriptUtterance(
                                "SPEAKER_0",
                                1,
                                2,
                                "알려진 화자의 내용"
                        )
                )
        );

        // when
        transcriptSegmentRepository.saveAll(segments);

        // then
        entityManager.flush();
        entityManager.clear();
        List<TranscriptSegment> restored = transcriptSegmentRepository.findAllByTranscriptId(transcriptId);
        assertThat(restored).extracting(TranscriptSegment::getSpeakerNumber)
                .containsExactly(
                        null,
                        1
                );
        assertThat(restored).extracting(TranscriptSegment::getText)
                .containsExactly(
                        "누군가 말한 내용",
                        "알려진 화자의 내용"
                );
    }
}
