package com.knot.backend.document.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.knot.backend.auth.domain.AuthTokenProvider;
import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.DocumentFixtures;
import com.knot.backend.testsupport.TestApplicationProperties;
import com.knot.backend.testsupport.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@Tag("acceptance")
@ActiveProfiles("dev")
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestApplicationProperties
class DocumentListAcceptanceTest {
    private static final String PATH = "/api/v1/workspaces/{workspaceId}/documents";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private AuthTokenProvider tokens;
    @Autowired
    private JsonMapper json;
    private DocumentFixtures fixtures;
    private long workspaceId;
    private long memberId;
    private long recordingId;
    private long transcriptId;
    private long documentId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE TABLE members, workspaces RESTART IDENTITY CASCADE")
                .update();
        fixtures = new DocumentFixtures(jdbc);
        memberId = fixtures.saveMember("조회자");
        workspaceId = fixtures.saveWorkspace();
        fixtures.join(
                workspaceId,
                memberId
        );
        recordingId = fixtures.saveRecording(
                workspaceId,
                memberId,
                1850999
        );
        long transcript = fixtures.saveTranscript(recordingId);
        transcriptId = transcript;
        documentId = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcript,
                fixtures.saveJob(
                        transcript,
                        "SUCCEEDED"
                ),
                "운영 정책"
        );
        fixtures.target(
                documentId,
                memberId,
                null
        );
    }

    @Test
    @DisplayName("쿠키 인증으로 카드·폴더를 읽고 조회는 확인·보관 상태를 변경하지 않는다")
    void findDocuments_success_readOnlyContract() throws Exception {
        String before = fixtures.snapshot();

        mvc.perform(request(memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].topic").value("운영 정책"))
                .andExpect(jsonPath("$.topics[0].documentCount").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId))
                .andExpect(jsonPath("$.items[0].recordingSessionId").value(recordingId))
                .andExpect(jsonPath("$.items[0].title").value("문서 보관 정책"))
                .andExpect(jsonPath("$.items[0].summary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-06T00:00:00Z"))
                .andExpect(jsonPath("$.items[0].recordingDurationSeconds").value(1850))
                .andExpect(jsonPath("$.items[0].myConfirmationState").value("PENDING"))
                .andExpect(jsonPath("$.items[0].confirmationSummary.confirmedCount").value(0))
                .andExpect(jsonPath("$.items[0].confirmationSummary.pendingCount").value(1))
                .andExpect(jsonPath("$.items[0].confirmationSummary.excludedCount").value(0))
                .andExpect(jsonPath("$.items[0].confirmationSummary.recordingSessionId").doesNotExist())
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        assertThat(fixtures.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("응답 필드를 추가하지 않고 기존 topic 필드로 큰 주제를 표시한다")
    void findDocuments_success_broadFolderContract() throws Exception {
        // given
        long category = jdbc.sql("""
                INSERT INTO document_categories(workspace_id, name, normalized_name, description, kind, created_at)
                VALUES (:workspace, '운영', '운영', '서비스 운영 범위', 'NORMAL', now()) RETURNING id
                """)
                .param(
                        "workspace",
                        workspaceId
                )
                .query(Long.class)
                .single();
        jdbc.sql("""
                UPDATE documents SET category_id = :category, grouping_scheme = 'BROAD',
                    assignment_source = 'AI_NEW', assignment_rule_version = 'test' WHERE id = :id
                """)
                .param(
                        "category",
                        category
                )
                .param(
                        "id",
                        documentId
                )
                .update();

        // when & then
        mvc.perform(request(memberId))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$",
                                org.hamcrest.Matchers.aMapWithSize(3)
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.items[0]",
                                org.hamcrest.Matchers.aMapWithSize(10)
                        )
                )
                .andExpect(jsonPath("$.folders").doesNotExist())
                .andExpect(jsonPath("$.items[0].folderKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].folderName").doesNotExist())
                .andExpect(jsonPath("$.items[0].topic").value("운영"))
                .andExpect(jsonPath("$.topics[0].topic").value("운영"))
                .andExpect(jsonPath("$.topics[0].documentCount").value(1));
    }

    @Test
    @DisplayName("같은 녹음의 페이지를 이어 읽고 폴더 수는 마지막 페이지까지 전체 결과를 유지한다")
    void findDocuments_success_cursorAndFilters() throws Exception {
        long transcript = transcriptId;
        long newer = fixtures.saveDocument(
                workspaceId,
                recordingId,
                transcript,
                fixtures.saveJob(
                        transcript,
                        "SUCCEEDED"
                ),
                "개발"
        );
        fixtures.target(
                newer,
                memberId,
                null
        );
        String body = mvc.perform(
                request(memberId).param(
                        "size",
                        "1"
                )
                        .param(
                                "recordingSessionId",
                                Long.toString(recordingId)
                        )
                        .param(
                                "myConfirmation",
                                "PENDING"
                        )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(newer))
                .andExpect(jsonPath("$.topics.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String cursor = json.readValue(
                body,
                com.knot.backend.document.presentation.dto.response.DocumentListResponse.class
        )
                .nextCursor();

        mvc.perform(
                request(memberId).param(
                        "size",
                        "100"
                )
                        .param(
                                "cursor",
                                cursor
                        )
                        .param(
                                "recordingSessionId",
                                Long.toString(recordingId)
                        )
                        .param(
                                "myConfirmation",
                                "PENDING"
                        )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(documentId))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.topics.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        mvc.perform(
                request(memberId).param(
                        "cursor",
                        cursor
                )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("생성 후 가입자는 확인 대상이 아니며 없는 녹음은 빈 목록이다")
    void findDocuments_success_newMemberAndEmpty() throws Exception {
        long newcomer = fixtures.saveMember("신규");
        fixtures.join(
                workspaceId,
                newcomer
        );
        mvc.perform(
                request(newcomer).param(
                        "myConfirmation",
                        "NOT_REQUIRED"
                )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].myConfirmationState").value("NOT_REQUIRED"));
        mvc.perform(
                request(memberId).param(
                        "recordingSessionId",
                        Long.toString(Long.MAX_VALUE)
                )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics").isEmpty())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @ParameterizedTest
    @CsvSource({"size,0", "size,101", "size,invalid", "size,2147483648", "recordingSessionId,0",
            "recordingSessionId,-1", "recordingSessionId,invalid", "recordingSessionId,9223372036854775808",
            "myConfirmation,INVALID", "cursor,%%%", "cursor,''"})
    @DisplayName("형식·범위가 틀린 쿼리는 모두 INVALID_PARAMETER다")
    void findDocuments_failure_invalidParameter(
            String name,
            String value
    ) throws Exception {
        mvc.perform(
                request(memberId).param(
                        name,
                        value
                )
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이며 비멤버·탈퇴자·삭제 Workspace는 403이다")
    void findDocuments_failure_access() throws Exception {
        mvc.perform(
                get(
                        PATH,
                        workspaceId
                )
        )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        long stranger = fixtures.saveMember("외부인");
        mvc.perform(request(stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
        fixtures.leave(
                workspaceId,
                memberId
        );
        mvc.perform(request(memberId))
                .andExpect(status().isForbidden());
        fixtures.join(
                workspaceId,
                memberId
        );
        jdbc.sql("UPDATE workspaces SET deleted_at = :time WHERE id = :id")
                .param(
                        "time",
                        java.sql.Timestamp.from(DocumentFixtures.CREATED_AT)
                )
                .param(
                        "id",
                        workspaceId
                )
                .update();
        mvc.perform(request(memberId))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Swagger는 목록 쿼리·응답·인증·오류 계약을 노출한다")
    void findDocuments_success_openApi() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/documents'].get.parameters[*].name").value(
                                hasItems(
                                        "workspaceId",
                                        "cursor",
                                        "size",
                                        "myConfirmation",
                                        "recordingSessionId"
                                )
                        )
                )
                .andExpect(
                        jsonPath("$.paths['/api/v1/workspaces/{workspaceId}/documents'].get.responses['400']").exists()
                )
                .andExpect(
                        jsonPath("$.components.schemas.DocumentListResponse.required").value(
                                hasItems(
                                        "topics",
                                        "items",
                                        "nextCursor"
                                )
                        )
                )
                .andExpect(
                        jsonPath("$.components.schemas.DocumentCardResponse.properties.recordingSessionId").exists()
                );
    }

    @Test
    @DisplayName("size를 생략하면 50개를 반환하고 최대 100개 요청으로 나머지를 조회할 수 있다")
    void findDocuments_success_defaultAndMaximumSize() throws Exception {
        for (int index = 0; index < 50; index++) {
            long recording = fixtures.saveRecording(
                    workspaceId,
                    memberId,
                    1000
            );
            long transcript = fixtures.saveTranscript(recording);
            fixtures.saveDocument(
                    workspaceId,
                    recording,
                    transcript,
                    fixtures.saveJob(
                            transcript,
                            "SUCCEEDED"
                    ),
                    "운영 정책"
            );
        }
        mvc.perform(request(memberId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50))
                .andExpect(jsonPath("$.topics[0].documentCount").value(51))
                .andExpect(jsonPath("$.nextCursor").isString());
        mvc.perform(
                request(memberId).param(
                        "size",
                        "100"
                )
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(51))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    private MockHttpServletRequestBuilder request(long member) {
        return get(
                PATH,
                workspaceId
        ).cookie(
                new Cookie(
                        "KNOT_ACCESS_TOKEN",
                        tokens.issue(
                                AuthenticatedMember.of(
                                        member,
                                        "멤버",
                                        null
                                )
                        )
                )
        );
    }
}
