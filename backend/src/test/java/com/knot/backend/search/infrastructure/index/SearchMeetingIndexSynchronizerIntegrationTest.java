package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.search.application.SearchDocumentQuery;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@ActiveProfiles("dev")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
@Transactional
class SearchMeetingIndexSynchronizerIntegrationTest {

    @TempDir
    private Path directory;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SearchDocumentQuery query;

    @Test
    @DisplayName("지정 워크스페이스의 저장 문서만 색인하고 문서가 없는 녹음과 삭제 문서를 제외한다")
    void refresh_success_indexesOnlyStoredDocumentsForEnabledWorkspace() throws Exception {
        DocumentFixtures documents = new DocumentFixtures(jdbc);
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        long member = documents.saveMember("색인 검증");
        long workspace = documents.saveWorkspace();
        documents.join(
                workspace,
                member
        );
        long document = messages.saveDocument(
                workspace,
                member
        );
        long recording = documents.saveRecording(
                workspace,
                member,
                60_000
        );
        documents.saveTranscript(recording);
        long foreignWorkspace = documents.saveWorkspace();
        documents.join(
                foreignWorkspace,
                member
        );
        long foreignDocument = messages.saveDocument(
                foreignWorkspace,
                member
        );
        SearchMeetingIndexSource source = new SearchMeetingIndexSource(jdbc);
        SearchMeetingDocumentIndex index = new SearchMeetingDocumentIndex(
                directory,
                embeddings(),
                "e5-revision"
        );
        SearchMeetingIndexSynchronizer synchronizer = new SearchMeetingIndexSynchronizer(
                index,
                source,
                List.of(workspace)
        );

        assertThat(source.documents(workspace)).containsOnlyKeys(document);
        synchronizer.refresh();
        assertThat(
                index.find(
                        workspace,
                        "탐색",
                        Set.of(
                                document,
                                foreignDocument
                        ),
                        10
                )
        ).containsExactly(document);
        assertThat(
                index.find(
                        foreignWorkspace,
                        "탐색",
                        Set.of(foreignDocument),
                        10
                )
        ).isEmpty();

        jdbc.sql("DELETE FROM documents WHERE id = :id")
                .param(
                        "id",
                        document
                )
                .update();
        synchronizer.refresh();
        assertThat(
                index.find(
                        workspace,
                        "탐색",
                        Set.of(
                                document,
                                foreignDocument
                        ),
                        10
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("첫 항목의 문서 번호를 둘째 회의의 KST 날짜 조건에 전파하지 않는다")
    void retrieve_success_preservesItemScopesAndDistinguishesStaleIndex() throws Exception {
        DocumentFixtures documents = new DocumentFixtures(jdbc);
        SearchMessageFixtures messages = new SearchMessageFixtures(jdbc);
        long member = documents.saveMember("항목별 색인 검증");
        long workspace = documents.saveWorkspace();
        documents.join(
                workspace,
                member
        );
        long first = messages.saveDocument(
                workspace,
                member
        );
        long second = messages.saveDocument(
                workspace,
                member
        );
        setMeetingDate(
                first,
                Instant.parse("2026-11-14T14:59:00Z")
        );
        setMeetingDate(
                second,
                Instant.parse("2026-11-14T15:00:00Z")
        );
        Instant now = Instant.parse("2026-11-15T00:00:00Z");
        jdbc.sql("UPDATE documents SET created_at = :cutoff WHERE workspace_id = :workspace")
                .param(
                        "cutoff",
                        Timestamp.from(now)
                )
                .param(
                        "workspace",
                        workspace
                )
                .update();
        SearchToolScope scope = new SearchToolScope(
                workspace,
                member,
                1,
                2,
                3,
                UUID.randomUUID(),
                now,
                now.plusSeconds(60)
        );
        SearchToolAccess access = mock(SearchToolAccess.class);
        SearchMeetingIndexSource source = new SearchMeetingIndexSource(jdbc);
        SearchMeetingDocumentIndex index = new SearchMeetingDocumentIndex(
                directory,
                embeddings(),
                "e5-revision"
        );
        SearchMeetingHybridRetrieval retrieval = new SearchMeetingHybridRetrieval(
                index,
                source,
                query,
                access,
                List.of(workspace)
        );
        String question = "1. 문서 #" + first + "의 결정을 알려줘\n2. 오늘 회의에서 무슨 이야기를 했어?";

        assertThat(
                retrieval.retrieve(
                        scope,
                        question
                )
        ).isEmpty();
        assertThat(
                index.reconcile(
                        workspace,
                        () -> source.documents(workspace)
                )
        ).isTrue();
        assertThat(
                retrieval.retrieve(
                        scope,
                        question
                )
                        .orElseThrow()
                        .documents()
        ).extracting(SearchDocumentResult::documentId)
                .containsExactly(
                        first,
                        second
                );
        verify(
                access,
                times(3)
        ).validate(scope);

        jdbc.sql("UPDATE documents SET content = '변경된 결정' WHERE id = :id")
                .param(
                        "id",
                        second
                )
                .update();
        SearchRetrievalResult partial = retrieval.retrieve(
                scope,
                question
        )
                .orElseThrow();
        assertThat(partial.documents()).extracting(SearchDocumentResult::documentId)
                .containsExactly(first);
        assertThat(partial.items()).containsExactly(
                new SearchRetrievalResult.Item(
                        1,
                        SearchRetrievalResult.ItemStatus.MATCHED,
                        List.of(first)
                ),
                new SearchRetrievalResult.Item(
                        2,
                        SearchRetrievalResult.ItemStatus.UNAVAILABLE,
                        List.of()
                )
        );
    }

    private void setMeetingDate(
            long documentId,
            Instant date
    ) {
        jdbc.sql("""
                UPDATE recording_sessions
                SET started_at = :date, ended_at = :ended, last_seen_at = :ended
                WHERE id = (SELECT recording_session_id FROM documents WHERE id = :document)
                """)
                .param(
                        "date",
                        Timestamp.from(date)
                )
                .param(
                        "ended",
                        Timestamp.from(date.plusSeconds(60))
                )
                .param(
                        "document",
                        documentId
                )
                .update();
    }

    private SearchE5EmbeddingClient embeddings() {
        SearchE5EmbeddingClient embeddings = mock(SearchE5EmbeddingClient.class);
        float[] vector = new float[SearchE5EmbeddingClient.DIMENSIONS];
        vector[0] = 1;
        given(embeddings.embedQuery(anyString())).willReturn(vector);
        given(embeddings.embedPassages(anyList())).willAnswer(invocation -> {
            List<String> passages = invocation.getArgument(0);
            return passages.stream()
                    .map(passage -> vector)
                    .toList();
        });
        return embeddings;
    }
}
