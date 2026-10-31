package com.knot.backend.recording.infrastructure.persistence;

import com.knot.backend.testsupport.RecordingConnectionPolicyFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.knot.backend.recording.domain.model.RecordingSession;
import com.knot.backend.recording.domain.repository.RecordingSessionRepository;
import com.knot.backend.recording.domain.model.RecordingStatus;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@Import({TestcontainersConfiguration.class, RecordingSessionRepositoryAdapter.class})
@DataJpaTest
class RecordingSessionRepositoryIntegrationTest {
    private static final String CONTROL_TOKEN_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final Instant STARTED_AT = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private RecordingSessionRepository recordingSessionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DisplayName("녹음 세션을 저장하고 requestId로 조회한다")
    @Test
    void save_success() {
        // given
        long memberId = saveMember("recording-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "녹음 팀"
        );
        UUID requestId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        RecordingSession recordingSession = startRecording(
                workspaceId,
                memberId,
                requestId
        );
        RecordingSession savedRecordingSession = saveAndFlush(recordingSession);
        entityManager.clear();

        // when
        RecordingSession foundRecordingSession = recordingSessionRepository
                .findByMemberIdAndRequestIdForUpdate(
                        memberId,
                        requestId
                )
                .orElseThrow();

        // then
        assertThat(foundRecordingSession.getId()).isEqualTo(savedRecordingSession.getId());
        assertThat(foundRecordingSession.getStatus()).isEqualTo(RecordingStatus.RECORDING);
        assertThat(foundRecordingSession.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(storedControlTokenHash(savedRecordingSession.getId())).isEqualTo(CONTROL_TOKEN_HASH);
    }

    @DisplayName("한 멤버는 활성 녹음을 하나만 가질 수 있다")
    @Test
    void save_failure_duplicateActiveMember() {
        // given
        long memberId = saveMember("active-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "활성 팀"
        );
        saveAndFlush(
                startRecording(
                        workspaceId,
                        memberId,
                        UUID.fromString("11111111-1111-1111-1111-111111111111")
                )
        );
        RecordingSession secondRecordingSession = startRecording(
                workspaceId,
                memberId,
                UUID.fromString("22222222-2222-2222-2222-222222222222")
        );

        // when
        Throwable thrown = catchThrowable(() -> saveAndFlush(secondRecordingSession));

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
    }

    @DisplayName("종료된 녹음은 활성 녹음 제약에서 제외된다")
    @Test
    void save_success_afterEndedRecording() {
        // given
        long memberId = saveMember("ended-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "종료 팀"
        );
        RecordingSession endedRecordingSession = startRecording(
                workspaceId,
                memberId,
                UUID.fromString("11111111-1111-1111-1111-111111111111")
        );
        endedRecordingSession.end(
                Instant.parse("2026-10-01T00:00:10Z"),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(endedRecordingSession);
        RecordingSession newRecordingSession = startRecording(
                workspaceId,
                memberId,
                UUID.fromString("22222222-2222-2222-2222-222222222222")
        );

        // when
        RecordingSession savedRecordingSession = saveAndFlush(newRecordingSession);

        // then
        assertThat(savedRecordingSession.getId()).isPositive();
        assertThat(recordingSessionRepository.findAllActiveByMemberIdForUpdate(memberId))
                .extracting(RecordingSession::getId)
                .containsExactly(savedRecordingSession.getId());
    }

    @DisplayName("존재하지 않는 워크스페이스나 멤버의 녹음 세션은 저장할 수 없다")
    @Test
    void save_failure_foreignKey() {
        // given
        RecordingSession recordingSession = startRecording(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                UUID.fromString("11111111-1111-1111-1111-111111111111")
        );

        // when
        Throwable thrown = catchThrowable(() -> saveAndFlush(recordingSession));

        // then
        assertThat(thrown).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    @DisplayName("트랜잭션이 rollback되면 녹음 세션도 저장되지 않는다")
    @Test
    void save_success_rollback() {
        // given
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when
        transactionTemplate.executeWithoutResult(status -> {
            long memberId = saveMember("rollback-member");
            long workspaceId = saveWorkspaceWithMember(
                    memberId,
                    "롤백 팀"
            );
            RecordingSession recordingSession = startRecording(
                    workspaceId,
                    memberId,
                    UUID.fromString("11111111-1111-1111-1111-111111111111")
            );
            recordingSessionRepository.save(recordingSession);
            entityManager.flush();
            status.setRollbackOnly();
        });
        entityManager.clear();

        // then
        assertThat(countRecordingSessions()).isZero();
    }

    @Test
    @DisplayName("일시정지한 세션도 다른 워크스페이스의 활성 녹음 생성을 막는다")
    void save_failure_pausedSessionAcrossWorkspaces() {
        // given
        long memberId = saveMember("paused-member");
        long firstWorkspaceId = saveWorkspaceWithMember(
                memberId,
                "첫 팀"
        );
        long secondWorkspaceId = saveWorkspaceWithMember(
                memberId,
                "다음 팀"
        );
        RecordingSession first = startRecording(
                firstWorkspaceId,
                memberId,
                UUID.randomUUID()
        );
        first.pause(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(first);
        RecordingSession second = startRecording(
                secondWorkspaceId,
                memberId,
                UUID.randomUUID()
        );

        // when
        Throwable failure = catchThrowable(() -> saveAndFlush(second));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.ACTIVE_RECORDING_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("종료된 세션의 요청 키도 같은 멤버가 다른 세션에 재사용하지 못한다")
    void save_failure_reusesEndedRequestId() {
        // given
        long memberId = saveMember("key-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "키 팀"
        );
        UUID requestId = UUID.randomUUID();
        RecordingSession first = startRecording(
                workspaceId,
                memberId,
                requestId
        );
        first.end(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(first);
        RecordingSession second = startRecording(
                workspaceId,
                memberId,
                requestId
        );

        // when
        Throwable failure = catchThrowable(() -> saveAndFlush(second));

        // then
        assertThat(failure).isInstanceOf(RecordingException.class)
                .extracting("errorCode")
                .isEqualTo(RecordingErrorCode.RECORDING_START_REQUEST_CONFLICT);
    }

    @Test
    @DisplayName("폐기한 녹음은 DISCARDED로 저장되고 활성 녹음 제약에서 제외된다")
    void save_success_afterDiscardedRecording() {
        // given
        long memberId = saveMember("discard-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "폐기 팀"
        );
        RecordingSession discarded = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        discarded.discard(STARTED_AT.plusSeconds(10));
        long discardedId = saveAndFlush(discarded).getId();
        RecordingSession next = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );

        // when
        RecordingSession saved = saveAndFlush(next);

        // then
        assertThat(saved.getId()).isPositive();
        assertThat(storedStatus(discardedId)).isEqualTo("DISCARDED");
    }

    @Test
    @DisplayName("DB는 중단 시각이 없는 폐기 녹음을 거부한다")
    void update_failure_discardedWithoutEndedAt() {
        // given
        long memberId = saveMember("check-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "제약 팀"
        );
        long recordingSessionId = saveAndFlush(
                startRecording(
                        workspaceId,
                        memberId,
                        UUID.randomUUID()
                )
        ).getId();

        // when
        Throwable failure = catchThrowable(
                () -> jdbcClient.sql("""
                        UPDATE recording_sessions
                        SET status = 'DISCARDED', current_interval_started_at = NULL
                        WHERE id = :recordingSessionId
                        """)
                        .param(
                                "recordingSessionId",
                                recordingSessionId
                        )
                        .update()
        );

        // then
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("멤버별 활성 녹음 조회는 해당 Workspace의 본인 진행 중 녹음만 반환한다")
    void findAllActiveByWorkspaceIdAndMemberIdForUpdate_success() {
        // given
        long memberId = saveMember("leaving-member");
        long otherMemberId = saveMember("staying-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "탈퇴 팀"
        );
        RecordingSession own = saveAndFlush(
                startRecording(
                        workspaceId,
                        memberId,
                        UUID.randomUUID()
                )
        );
        saveAndFlush(
                startRecording(
                        workspaceId,
                        otherMemberId,
                        UUID.randomUUID()
                )
        );
        entityManager.clear();

        // when
        List<RecordingSession> found = recordingSessionRepository.findAllActiveByWorkspaceIdAndMemberIdForUpdate(
                workspaceId,
                memberId
        );

        // then
        assertThat(found).extracting(RecordingSession::getId)
                .containsExactly(own.getId());
    }

    @Test
    @DisplayName("Workspace 활성 녹음 조회는 다른 Workspace와 종료된 녹음을 제외한다")
    void findAllActiveByWorkspaceIdForUpdate_success() {
        // given
        long firstMemberId = saveMember("first-member");
        long secondMemberId = saveMember("second-member");
        long endedMemberId = saveMember("ended-member");
        long otherMemberId = saveMember("other-member");
        long workspaceId = saveWorkspaceWithMember(
                firstMemberId,
                "삭제 팀"
        );
        long otherWorkspaceId = saveWorkspaceWithMember(
                otherMemberId,
                "다른 팀"
        );
        RecordingSession recording = saveAndFlush(
                startRecording(
                        workspaceId,
                        firstMemberId,
                        UUID.randomUUID()
                )
        );
        RecordingSession paused = startRecording(
                workspaceId,
                secondMemberId,
                UUID.randomUUID()
        );
        paused.pause(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(paused);
        RecordingSession ended = startRecording(
                workspaceId,
                endedMemberId,
                UUID.randomUUID()
        );
        ended.end(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(ended);
        saveAndFlush(
                startRecording(
                        otherWorkspaceId,
                        otherMemberId,
                        UUID.randomUUID()
                )
        );
        entityManager.clear();

        // when
        List<RecordingSession> found = recordingSessionRepository.findAllActiveByWorkspaceIdForUpdate(workspaceId);

        // then
        assertThat(found).extracting(RecordingSession::getId)
                .containsExactly(
                        recording.getId(),
                        paused.getId()
                );
    }

    @Test
    @DisplayName("현재 녹음 조회는 요청 Workspace에서 본인의 활성 녹음만 반환하고 종료된 녹음과 다른 멤버 녹음은 제외한다")
    void findActiveByWorkspaceIdAndMemberId_success() {
        // given
        long memberId = saveMember("current-member");
        long otherMemberId = saveMember("other-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "현재 녹음 팀"
        );
        RecordingSession ended = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        ended.end(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(ended);
        RecordingSession paused = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        paused.pause(
                STARTED_AT.plusSeconds(20),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(paused);
        saveAndFlush(
                startRecording(
                        workspaceId,
                        otherMemberId,
                        UUID.randomUUID()
                )
        );
        entityManager.clear();

        // when
        Optional<RecordingSession> found = recordingSessionRepository.findActiveByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        );

        // then
        assertThat(found).hasValueSatisfying(session -> {
            assertThat(session.getId()).isEqualTo(paused.getId());
            assertThat(session.getStatus()).isEqualTo(RecordingStatus.PAUSED);
        });
    }

    @Test
    @DisplayName("다른 Workspace에서 진행 중인 본인 녹음은 현재 녹음 조회에서 반환하지 않는다")
    void findActiveByWorkspaceIdAndMemberId_success_otherWorkspaceExcluded() {
        // given
        long memberId = saveMember("cross-member");
        long recordingWorkspaceId = saveWorkspaceWithMember(
                memberId,
                "녹음 팀"
        );
        long otherWorkspaceId = saveWorkspaceWithMember(
                memberId,
                "다른 팀"
        );
        saveAndFlush(
                startRecording(
                        recordingWorkspaceId,
                        memberId,
                        UUID.randomUUID()
                )
        );
        entityManager.clear();

        // when
        Optional<RecordingSession> found = recordingSessionRepository.findActiveByWorkspaceIdAndMemberId(
                otherWorkspaceId,
                memberId
        );

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("폐기된 녹음은 현재 녹음 조회에서 반환하지 않는다")
    void findActiveByWorkspaceIdAndMemberId_success_discardedExcluded() {
        // given
        long memberId = saveMember("discarded-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "폐기 팀"
        );
        RecordingSession discarded = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        discarded.discard(STARTED_AT.plusSeconds(10));
        saveAndFlush(discarded);
        entityManager.clear();

        // when
        Optional<RecordingSession> found = recordingSessionRepository.findActiveByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        );

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("최신 녹음 조회는 상태와 관계없이 시작 시각이 가장 최근인 본인 녹음 한 건을 반환한다")
    void findLatestByWorkspaceIdAndMemberId_success_startedAtBeforeId() {
        // given
        long memberId = saveMember("latest-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "최신 팀"
        );
        RecordingSession latest = RecordingSession.start(
                workspaceId,
                memberId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                CONTROL_TOKEN_HASH,
                STARTED_AT.plusSeconds(100)
        );
        latest.end(
                STARTED_AT.plusSeconds(110),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        saveAndFlush(latest);
        RecordingSession earlier = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        earlier.discard(STARTED_AT.plusSeconds(10));
        saveAndFlush(earlier);
        entityManager.clear();

        // when
        Optional<RecordingSession> found = recordingSessionRepository.findLatestByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        );

        // then
        assertThat(found).hasValueSatisfying(session -> {
            assertThat(session.getId()).isEqualTo(latest.getId());
            assertThat(session.getStatus()).isEqualTo(RecordingStatus.ENDED);
        });
    }

    @Test
    @DisplayName("시작 시각이 같으면 ID가 큰 녹음을 선택하고 다른 멤버와 Workspace 녹음은 제외한다")
    void findLatestByWorkspaceIdAndMemberId_success_tieAndIsolation() {
        // given
        long memberId = saveMember("tie-member");
        long otherMemberId = saveMember("other-tie-member");
        long workspaceId = saveWorkspaceWithMember(
                memberId,
                "현재 팀"
        );
        long otherWorkspaceId = saveWorkspaceWithMember(
                memberId,
                "다른 팀"
        );
        saveEndedRecording(
                workspaceId,
                memberId
        );
        RecordingSession latest = saveEndedRecording(
                workspaceId,
                memberId
        );
        saveEndedRecording(
                workspaceId,
                otherMemberId
        );
        saveEndedRecording(
                otherWorkspaceId,
                memberId
        );
        entityManager.clear();

        // when
        Optional<RecordingSession> found = recordingSessionRepository.findLatestByWorkspaceIdAndMemberId(
                workspaceId,
                memberId
        );

        // then
        assertThat(found).hasValueSatisfying(session -> assertThat(session.getId()).isEqualTo(latest.getId()));
    }

    private RecordingSession saveEndedRecording(
            long workspaceId,
            long memberId
    ) {
        RecordingSession session = startRecording(
                workspaceId,
                memberId,
                UUID.randomUUID()
        );
        session.end(
                STARTED_AT.plusSeconds(10),
                RecordingConnectionPolicyFixture.DEFAULT
        );
        return saveAndFlush(session);
    }

    private String storedStatus(long recordingSessionId) {
        return jdbcClient.sql("SELECT status FROM recording_sessions WHERE id = :recordingSessionId")
                .param(
                        "recordingSessionId",
                        recordingSessionId
                )
                .query(String.class)
                .single();
    }

    private RecordingSession saveAndFlush(RecordingSession recordingSession) {
        RecordingSession savedRecordingSession = recordingSessionRepository.save(recordingSession);
        entityManager.flush();
        return savedRecordingSession;
    }

    private RecordingSession startRecording(
            long workspaceId,
            long memberId,
            UUID requestId
    ) {
        return RecordingSession.start(
                workspaceId,
                memberId,
                requestId,
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                CONTROL_TOKEN_HASH,
                STARTED_AT
        );
    }

    private long saveMember(String nickname) {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES (:nickname, NULL)
                RETURNING id
                """)
                .param(
                        "nickname",
                        nickname
                )
                .query(Long.class)
                .single();
    }

    private long saveWorkspaceWithMember(
            long memberId,
            String name
    ) {
        long workspaceId = jdbcClient.sql("""
                INSERT INTO workspaces (name, created_at)
                VALUES (:name, CAST(:createdAt AS TIMESTAMPTZ))
                RETURNING id
                """)
                .param(
                        "name",
                        name
                )
                .param(
                        "createdAt",
                        STARTED_AT.toString()
                )
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at, last_viewed)
                VALUES (:workspaceId, :memberId, 'OWNER', CAST(:joinedAt AS TIMESTAMPTZ), FALSE)
                """)
                .param(
                        "workspaceId",
                        workspaceId
                )
                .param(
                        "memberId",
                        memberId
                )
                .param(
                        "joinedAt",
                        STARTED_AT.toString()
                )
                .update();
        return workspaceId;
    }

    private String storedControlTokenHash(long recordingSessionId) {
        return jdbcClient.sql("SELECT control_token_hash FROM recording_sessions WHERE id = :recordingSessionId")
                .param(
                        "recordingSessionId",
                        recordingSessionId
                )
                .query(String.class)
                .single();
    }

    private int countRecordingSessions() {
        return jdbcClient.sql("SELECT COUNT(*) FROM recording_sessions")
                .query(Integer.class)
                .single();
    }
}
