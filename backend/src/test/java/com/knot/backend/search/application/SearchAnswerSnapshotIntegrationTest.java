package com.knot.backend.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.dto.command.SearchQuestionCommand;
import com.knot.backend.search.application.dto.result.SearchAnswerSnapshotResult;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchQuestionAcceptedResult;
import com.knot.backend.search.infrastructure.persistence.SearchMessageListQueryAdapter;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class SearchAnswerSnapshotIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private SearchQuestionIntakeService intake;
    @Autowired
    private SearchAnswerExecutionService executions;
    @Autowired
    private SearchAnswerSnapshotService snapshots;
    @MockitoBean
    private SearchGenerationAvailability availability;
    @MockitoSpyBean
    private SearchMessageListQueryAdapter messages;

    @Test
    @DisplayName("snapshot 조회 도중 완료가 커밋되어도 본문·상태·근거는 같은 DB 시점을 유지한다")
    void read_success_consistentDuringCompletion() throws Exception {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        DocumentFixtures fixtures = new DocumentFixtures(jdbc);
        long member = fixtures.saveMember("질문자");
        long workspace = fixtures.saveWorkspace();
        fixtures.join(
                workspace,
                member
        );
        SearchQuestionAcceptedResult accepted = intake.acceptFirst(
                workspace,
                member,
                new SearchQuestionCommand(
                        "질문",
                        UUID.randomUUID()
                )
        );
        SearchAnswerWorkResult work = executions.claim(accepted.answerMessageId())
                .orElseThrow();
        long document = new SearchMessageFixtures(jdbc).saveDocument(
                workspace,
                member
        );
        jdbc.sql("UPDATE documents SET status='ARCHIVED', archived_at=created_at WHERE id=:id")
                .param(
                        "id",
                        document
                )
                .update();
        executions.append(
                work.answerId(),
                work.token(),
                "처음"
        );
        Instant firstPersistedAt = jdbc.sql("SELECT updated_at FROM search_conversations WHERE id=:id")
                .param(
                        "id",
                        accepted.conversationId()
                )
                .query(Instant.class)
                .single();
        CountDownLatch readBody = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            readBody.countDown();
            assertThat(
                    resume.await(
                            5,
                            TimeUnit.SECONDS
                    )
            ).isTrue();
            return call.callRealMethod();
        }).when(messages)
                .findEvidences(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        any()
                );
        try (java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<SearchAnswerSnapshotResult> reading = executor.submit(
                    () -> snapshots.read(
                            workspace,
                            member,
                            accepted.conversationId(),
                            work.answerId()
                    )
            );
            try {
                assertThat(
                        readBody.await(
                                5,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
                assertThat(
                        executions.append(
                                work.answerId(),
                                work.token(),
                                " 이후"
                        )
                ).isTrue();
                assertThat(
                        executions.complete(
                                work.answerId(),
                                work.token(),
                                List.of(document),
                                Set.of(document)
                        )
                ).isTrue();
            } finally {
                resume.countDown();
            }
            SearchAnswerSnapshotResult before = reading.get(
                    5,
                    TimeUnit.SECONDS
            );
            assertThat(before.content()).isEqualTo("처음");
            assertThat(before.contentUpdatedAt()).isEqualTo(firstPersistedAt);
            assertThat(
                    before.status()
                            .name()
            ).isEqualTo("STREAMING");
            assertThat(before.evidences()).isEmpty();
            SearchAnswerSnapshotResult after = snapshots.read(
                    workspace,
                    member,
                    accepted.conversationId(),
                    work.answerId()
            );
            assertThat(after.content()).isEqualTo("처음 이후\n\n근거 문서의 변경 여부를 확인할 수 없어요.");
            assertThat(
                    after.status()
                            .name()
            ).isEqualTo("COMPLETED");
            assertThat(after.evidences()).extracting("documentId")
                    .containsExactly(document);
        }
    }
}
