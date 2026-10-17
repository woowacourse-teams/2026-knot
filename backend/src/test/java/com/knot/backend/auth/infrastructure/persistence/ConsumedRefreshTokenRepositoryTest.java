package com.knot.backend.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.AuthSession;
import com.knot.backend.auth.domain.repository.AuthSessionRepository;
import com.knot.backend.auth.domain.model.ConsumedRefreshToken;
import com.knot.backend.auth.domain.repository.ConsumedRefreshTokenRepository;
import com.knot.backend.member.domain.Member;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.SharedPostgresAuthRepositoryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AuthSessionRepositoryImpl.class, ConsumedRefreshTokenRepositoryImpl.class,
        SharedPostgresTestConfiguration.class})
@TestApplicationProperties
@TestConstructor(autowireMode = AutowireMode.ALL)
class ConsumedRefreshTokenRepositoryTest extends SharedPostgresAuthRepositoryTestSupport {
    private final AuthSessionRepository authSessionRepository;
    private final ConsumedRefreshTokenRepository consumedRefreshTokenRepository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    ConsumedRefreshTokenRepositoryTest(
            AuthSessionRepository authSessionRepository,
            ConsumedRefreshTokenRepository consumedRefreshTokenRepository,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate
    ) {
        this.authSessionRepository = authSessionRepository;
        this.consumedRefreshTokenRepository = consumedRefreshTokenRepository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("소비한 refresh 토큰 해시를 세션과 함께 조회할 수 있다")
    void save_success_findByRefreshTokenHash() {
        // given
        AuthSession session = createSession("a".repeat(64));
        String consumedHash = "b".repeat(64);
        Instant consumedAt = Instant.parse("2026-10-01T00:00:00Z");
        consumedRefreshTokenRepository.save(
                ConsumedRefreshToken.create(
                        session.getId(),
                        consumedHash,
                        consumedAt
                )
        );
        entityManager.clear();

        // when
        ConsumedRefreshToken consumedRefreshToken = consumedRefreshTokenRepository.findByRefreshTokenHash(consumedHash)
                .orElseThrow();

        // then
        assertThat(consumedRefreshToken.getAuthSessionId()).isEqualTo(session.getId());
        assertThat(consumedRefreshToken.getConsumedAt()).isEqualTo(consumedAt);
        assertThat(consumedRefreshToken.getRefreshTokenHash()).isEqualTo(consumedHash);
    }

    @Test
    @DisplayName("같은 refresh 토큰 해시를 여러 번 소비 처리할 수 없다")
    void save_failure_duplicateRefreshTokenHash() {
        // given
        AuthSession session = createSession("a".repeat(64));
        String consumedHash = "b".repeat(64);
        consumedRefreshTokenRepository.save(
                ConsumedRefreshToken.create(
                        session.getId(),
                        consumedHash,
                        Instant.parse("2026-10-01T00:00:00Z")
                )
        );
        entityManager.flush();

        // when & then
        assertThatThrownBy(
                () -> consumedRefreshTokenRepository.save(
                        ConsumedRefreshToken.create(
                                session.getId(),
                                consumedHash,
                                Instant.parse("2026-10-01T00:00:01Z")
                        )
                )
        ).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("소비 토큰 이력에는 JWT 원문을 저장하지 않는다")
    void schema_success_hashOnlyColumns() {
        // given
        Set<String> columns = Set.copyOf(
                jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_name = 'auth_session_consumed_refresh_tokens'",
                        String.class
                )
        );

        // when & then
        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "refresh_token_hash",
                "auth_session_id",
                "consumed_at"
        );
    }

    @Test
    @DisplayName("존재하지 않는 세션의 토큰 소비 이력은 저장할 수 없다")
    void save_failure_missingAuthSession() {
        // when & then
        assertThatThrownBy(
                () -> consumedRefreshTokenRepository.save(
                        ConsumedRefreshToken.create(
                                999999L,
                                "b".repeat(64),
                                Instant.parse("2026-10-01T00:00:00Z")
                        )
                )
        ).isInstanceOf(AuthException.class);
    }

    private AuthSession createSession(String currentRefreshTokenHash) {
        Member member = Member.create(
                "흑곰",
                null
        );
        entityManager.persist(member);
        return authSessionRepository.save(
                AuthSession.create(
                        member.getId(),
                        currentRefreshTokenHash,
                        Instant.parse("2026-10-01T00:00:00Z")
                )
        );
    }
}
