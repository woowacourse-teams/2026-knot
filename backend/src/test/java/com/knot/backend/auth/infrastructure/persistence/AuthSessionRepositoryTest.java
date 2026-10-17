package com.knot.backend.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knot.backend.auth.domain.AuthException;
import com.knot.backend.auth.domain.model.AuthSession;
import com.knot.backend.auth.domain.repository.AuthSessionRepository;
import com.knot.backend.member.domain.Member;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.SharedPostgresAuthRepositoryTestSupport;
import com.knot.backend.testsupport.SharedPostgresTestConfiguration;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Import({AuthSessionRepositoryImpl.class, ConsumedRefreshTokenRepositoryImpl.class,
        SharedPostgresTestConfiguration.class})
@TestApplicationProperties
@TestConstructor(autowireMode = AutowireMode.ALL)
class AuthSessionRepositoryTest extends SharedPostgresAuthRepositoryTestSupport {
    private final AuthSessionRepository repository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    AuthSessionRepositoryTest(
            AuthSessionRepository repository,
            EntityManager entityManager,
            JdbcTemplate jdbcTemplate
    ) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    @DisplayName("로그인 세션의 해시와 만료 시각을 PostgreSQL에 저장한다")
    void save_success() {
        // given
        Member member = Member.create(
                "흑곰",
                null
        );
        entityManager.persist(member);
        Instant now = Instant.parse("2026-10-01T00:00:00Z");

        // when
        AuthSession saved = repository.save(
                AuthSession.create(
                        member.getId(),
                        "a".repeat(64),
                        now
                )
        );

        // then
        entityManager.clear();
        AuthSession loaded = entityManager.find(
                AuthSession.class,
                saved.getId()
        );
        assertThat(loaded.getRefreshTokenHash()).isEqualTo("a".repeat(64));
        assertThat(loaded.getCreatedAt()).isEqualTo(now);
        assertThat(loaded.getExpiresAt()).isEqualTo(now.plusSeconds(7 * 86400));
        assertThat(loaded.getAbsoluteExpiresAt()).isEqualTo(now.plusSeconds(30 * 86400));
        assertThat(loaded.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("같은 refresh 해시를 가진 세션은 중복 저장할 수 없다")
    void save_failure_duplicateHash() {
        // given
        Member member = Member.create(
                "흑곰",
                null
        );
        entityManager.persist(member);
        repository.save(
                AuthSession.create(
                        member.getId(),
                        "a".repeat(64),
                        Instant.now()
                )
        );

        // when & then
        assertThatThrownBy(
                () -> repository.save(
                        AuthSession.create(
                                member.getId(),
                                "a".repeat(64),
                                Instant.now()
                        )
                )
        ).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("존재하지 않는 회원의 세션은 외래 키 제약으로 저장할 수 없다")
    void save_failure_missingMember() {
        // when & then
        assertThatThrownBy(
                () -> repository.save(
                        AuthSession.create(
                                999999L,
                                "a".repeat(64),
                                Instant.now()
                        )
                )
        ).isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("refresh 토큰 해시로 현재 로그인 세션을 찾는다")
    void findByRefreshTokenHash_success() {
        // given
        Member member = Member.create(
                "흑곰",
                null
        );
        entityManager.persist(member);
        String refreshTokenHash = "b".repeat(64);
        repository.save(
                AuthSession.create(
                        member.getId(),
                        refreshTokenHash,
                        Instant.now()
                )
        );
        entityManager.clear();

        // when
        AuthSession session = repository.findByRefreshTokenHash(refreshTokenHash)
                .orElseThrow();

        // then
        assertThat(session.getMemberId()).isEqualTo(member.getId());
        assertThat(session.getRefreshTokenHash()).isEqualTo(refreshTokenHash);
    }

    @Test
    @DisplayName("등록되지 않은 refresh 토큰 해시는 로그인 세션을 찾지 못한다")
    void findByRefreshTokenHash_failure_unknownHash() {
        // when & then
        assertThat(repository.findByRefreshTokenHash("c".repeat(64))).isEmpty();
    }

    @Test
    @DisplayName("세션 만료 시각은 발급 시각 이후이면서 절대 만료 이하여야 한다")
    void schema_failure_invalidExpiry() {
        // given
        Member member = Member.create(
                "흑곰",
                null
        );
        entityManager.persist(member);
        AuthSession session = repository.save(
                AuthSession.create(
                        member.getId(),
                        "a".repeat(64),
                        Instant.now()
                )
        );

        // when & then
        assertThatThrownBy(
                () -> jdbcTemplate.update(
                        "UPDATE auth_sessions SET expires_at = created_at WHERE id = ?",
                        session.getId()
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
