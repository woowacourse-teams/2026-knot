package com.knot.backend.search.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.search.SearchFixtures;
import com.knot.backend.search.SearchMessageFixtures;
import com.knot.backend.testsupport.TestcontainersConfiguration;
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
@Import(TestcontainersConfiguration.class)
class SearchEvidenceStorageIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    private SearchMessageFixtures messages;
    private long workspaceId;
    private long memberId;
    private long answerId;
    private long documentId;

    @BeforeEach
    void setUp() {
        DocumentFixtures base = new DocumentFixtures(jdbc);
        messages = new SearchMessageFixtures(jdbc);
        workspaceId = base.saveWorkspace();
        memberId = base.saveMember("탐색자");
        long conversationId = new SearchFixtures(jdbc).saveConversation(
                workspaceId,
                memberId,
                "질문",
                "답변"
        );
        answerId = messages.messageId(
                conversationId,
                2
        );
        documentId = messages.saveDocument(
                workspaceId,
                memberId
        );
    }

    @Test
    @DisplayName("답변에는 서로 다른 문서와 순위로 세 근거를 저장한다")
    void saveThree_success() {
        // given
        long second = messages.saveDocument(
                workspaceId,
                memberId
        );
        long third = messages.saveDocument(
                workspaceId,
                memberId
        );
        // when
        messages.saveEvidence(
                answerId,
                documentId,
                1
        );
        messages.saveEvidence(
                answerId,
                second,
                2
        );
        messages.saveEvidence(
                answerId,
                third,
                3
        );
        // then
        assertThat(
                jdbc.sql("SELECT count(*) FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(Long.class)
                        .single()
        ).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 문서는 다른 순위여도 중복 연결할 수 없다")
    void save_failure_duplicateDocument() {
        // given
        messages.saveEvidence(
                answerId,
                documentId,
                1
        );
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> messages.saveEvidence(
                        answerId,
                        documentId,
                        2
                )
        );
    }

    @Test
    @DisplayName("같은 순위에 다른 문서를 연결할 수 없다")
    void save_failure_duplicateRank() {
        // given
        messages.saveEvidence(
                answerId,
                documentId,
                1
        );
        long another = messages.saveDocument(
                workspaceId,
                memberId
        );
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> messages.saveEvidence(
                        answerId,
                        another,
                        1
                )
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    @DisplayName("양수가 아닌 근거 순위는 거절한다")
    void save_failure_invalidRank(int rank) {
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> messages.saveEvidence(
                        answerId,
                        documentId,
                        rank
                )
        );
    }

    @Test
    @DisplayName("카드 세 개 제한과 별개로 큰 실제 근거 순위를 저장한다")
    void save_success_rankBeyondCards() {
        messages.saveEvidence(
                answerId,
                documentId,
                40000
        );
        assertThat(
                jdbc.sql("SELECT rank FROM search_evidences WHERE message_id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .query(Integer.class)
                        .single()
        ).isEqualTo(40000);
    }

    @Test
    @DisplayName("존재하지 않는 문서는 연결할 수 없다")
    void save_failure_missingDocument() {
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> messages.saveEvidence(
                        answerId,
                        Long.MAX_VALUE,
                        1
                )
        );
    }

    @Test
    @DisplayName("존재하지 않는 메시지는 연결할 수 없다")
    void save_failure_missingMessage() {
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> messages.saveEvidence(
                        Long.MAX_VALUE,
                        documentId,
                        1
                )
        );
    }

    @Test
    @DisplayName("문서 삭제는 근거의 당시 ID만 남긴다")
    void delete_success_retainsHistoricalDocumentId() {
        // given
        messages.saveEvidence(
                answerId,
                documentId,
                1
        );
        // when & then
        assertThat(
                jdbc.sql("DELETE FROM documents WHERE id = :id")
                        .param(
                                "id",
                                documentId
                        )
                        .update()
        ).isEqualTo(1);
        assertThat(
                jdbc.sql("SELECT document_id FROM search_evidences WHERE live_document_id IS NULL")
                        .query(Long.class)
                        .list()
        ).containsExactly(documentId);
    }

    @Test
    @DisplayName("연결된 메시지는 물리 삭제할 수 없다")
    void delete_failure_referencedMessage() {
        // given
        messages.saveEvidence(
                answerId,
                documentId,
                1
        );
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> jdbc.sql("DELETE FROM search_messages WHERE id = :id")
                        .param(
                                "id",
                                answerId
                        )
                        .update()
        );
    }

    @Test
    @DisplayName("근거 순위는 null을 허용하지 않는다")
    void save_failure_nullRank() {
        // when & then
        assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
                () -> jdbc.sql(
                        "INSERT INTO search_evidences (message_id, document_id, rank) VALUES (:message, :document, NULL)"
                )
                        .param(
                                "message",
                                answerId
                        )
                        .param(
                                "document",
                                documentId
                        )
                        .update()
        );
    }
}
