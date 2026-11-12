package com.knot.backend.workspace.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.IsolatedPostgresTest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

@Tag("integration")
@IsolatedPostgresTest
@TestApplicationProperties
@SpringBootTest
@TestConstructor(autowireMode = AutowireMode.ALL)
class WorkspaceLastViewedServiceIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-31T00:00:00Z");
    private static final Instant JOINED_AT = Instant.parse("2026-08-31T00:01:00Z");

    private final WorkspaceService workspaceLastViewedService;
    private final JdbcClient jdbcClient;

    WorkspaceLastViewedServiceIntegrationTest(
            WorkspaceService workspaceLastViewedService,
            JdbcClient jdbcClient
    ) {
        this.workspaceLastViewedService = workspaceLastViewedService;
        this.jdbcClient = jdbcClient;
    }

    @BeforeEach
    void clearTables() {
        jdbcClient
                .sql("TRUNCATE TABLE workspace_members, workspaces, oauth_identities, members RESTART IDENTITY CASCADE")
                .update();
    }

    @DisplayName("서로 다른 워크스페이스를 동시에 갱신해도 마지막 조회 상태는 하나만 남는다")
    @Test
    void update_success_concurrentRequestsKeepSingleLastViewedWorkspace() throws Exception {
        // given
        long memberId = saveMember();
        long firstWorkspaceId = saveWorkspace("첫 팀");
        long secondWorkspaceId = saveWorkspace("두 번째 팀");
        saveWorkspaceMember(
                firstWorkspaceId,
                memberId
        );
        saveWorkspaceMember(
                secondWorkspaceId,
                memberId
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        Callable<Void> updateFirst = updateAfterBarrier(
                barrier,
                memberId,
                firstWorkspaceId
        );
        Callable<Void> updateSecond = updateAfterBarrier(
                barrier,
                memberId,
                secondWorkspaceId
        );

        try {
            // when
            Future<Void> firstResult = executorService.submit(updateFirst);
            Future<Void> secondResult = executorService.submit(updateSecond);
            firstResult.get(
                    10,
                    TimeUnit.SECONDS
            );
            secondResult.get(
                    10,
                    TimeUnit.SECONDS
            );

            // then
            List<Long> lastViewedWorkspaceIds = lastViewedWorkspaceIds(memberId);
            assertThat(lastViewedWorkspaceIds).hasSize(1)
                    .containsAnyOf(
                            firstWorkspaceId,
                            secondWorkspaceId
                    );
        } finally {
            executorService.shutdownNow();
        }
    }

    private Callable<Void> updateAfterBarrier(
            CyclicBarrier barrier,
            long memberId,
            long workspaceId
    ) {
        return () -> {
            barrier.await();
            workspaceLastViewedService.updateLastViewed(
                    memberId,
                    workspaceId
            );
            return null;
        };
    }

    private long saveMember() {
        return jdbcClient.sql("""
                INSERT INTO members (nickname, profile_image_url)
                VALUES ('concurrent-member', NULL)
                RETURNING id
                """)
                .query(Long.class)
                .single();
    }

    private long saveWorkspace(String name) {
        return jdbcClient.sql("""
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
                        CREATED_AT.toString()
                )
                .query(Long.class)
                .single();
    }

    private void saveWorkspaceMember(
            long workspaceId,
            long memberId
    ) {
        jdbcClient.sql("""
                INSERT INTO workspace_members (workspace_id, member_id, role, joined_at)
                VALUES (:workspaceId, :memberId, 'MEMBER', CAST(:joinedAt AS TIMESTAMPTZ))
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
                        JOINED_AT.toString()
                )
                .update();
    }

    private List<Long> lastViewedWorkspaceIds(long memberId) {
        return jdbcClient.sql("""
                SELECT workspace_id
                FROM workspace_members
                WHERE member_id = :memberId AND last_viewed
                """)
                .param(
                        "memberId",
                        memberId
                )
                .query(Long.class)
                .list();
    }
}
