package com.knot.backend.document.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.knot.backend.document.application.dto.request.DocumentCategoryInput;
import com.knot.backend.document.application.dto.result.DocumentGenerationResult;
import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.global.config.DocumentCategoryProperties;
import com.knot.backend.global.infrastructure.llm.LlmClient;
import com.knot.backend.global.infrastructure.llm.LlmCompletionRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class LlmDocumentCategoryAssignerTest {

    @Mock
    private LlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private LlmDocumentCategoryAssigner assigner;
    private DocumentCategoryInput input;

    @BeforeEach
    void setUp() {
        assigner = new LlmDocumentCategoryAssigner(
                client,
                new DocumentCategoryPrompt(
                        mapper,
                        new DocumentCategoryProperties()
                ),
                mapper
        );
        DocumentCategory category = DocumentCategory.create(
                1,
                "녹음·전사",
                "음성 처리",
                Instant.EPOCH
        );
        ReflectionTestUtils.setField(
                category,
                "id",
                12L
        );
        input = new DocumentCategoryInput(
                "녹음 품질",
                new DocumentGenerationResult(
                        "품질 검증",
                        "요약",
                        "본문"
                ),
                List.of(category)
        );
    }

    @Test
    @DisplayName("작성 완료 본문과 전체 정상 후보를 별도 요청으로 보내고 요청별 시간 제한을 전달한다")
    void assign_success_reuseWithBoundedRequest() {
        // given
        when(client.complete(any())).thenReturn("""
                {"existingCategoryId":12,"newName":null,"newDescription":null}
                """);

        // when
        var decision = assigner.assign(
                input,
                Duration.ofSeconds(7)
        );

        // then
        assertThat(decision.getExistingCategoryId()).isEqualTo(12);
        ArgumentCaptor<LlmCompletionRequest> captor = ArgumentCaptor.forClass(LlmCompletionRequest.class);
        org.mockito.Mockito.verify(client)
                .complete(captor.capture());
        LlmCompletionRequest request = captor.getValue();
        assertThat(request.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(
                request.options()
                        .thinkingEnabled()
        ).isFalse();
        assertThat(
                request.messages()
                        .getLast()
                        .content()
        ).contains(
                "agendaTopic",
                "categories",
                "음성 처리",
                "본문"
        );
        assertThat(
                request.messages()
                        .getFirst()
                        .content()
        ).contains(
                "기존",
                "기타",
                "신뢰"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"existingCategoryId\":99,\"newName\":null,\"newDescription\":null}",
            "{\"existingCategoryId\":12.5,\"newName\":null,\"newDescription\":null}",
            "{\"existingCategoryId\":\"12\",\"newName\":null,\"newDescription\":null}",
            "{\"existingCategoryId\":12,\"newName\":\"문서\",\"newDescription\":\"범위\"}",
            "{\"existingCategoryId\":null,\"newName\":null,\"newDescription\":null}",
            "{\"existingCategoryId\":null,\"newName\":\"기타\",\"newDescription\":\"범위\"}",
            "{\"existingCategoryId\":null,\"newName\":\" \",\"newDescription\":\"범위\"}",
            "{\"existingCategoryId\":null,\"newName\":\"새 주제\",\"newDescription\":null}",
            "{\"existingCategoryId\":12,\"newName\":null,\"newDescription\":null,\"extra\":1}",
            "{\"existingCategoryId\":12,\"existingCategoryId\":13,\"newName\":null,\"newDescription\":null}",
            "{\"existingCategoryId\":12,\"newName\":null,\"newDescription\":null} {}"})
    @DisplayName("다른 후보 ID, 혼합 모드, 예약명과 손상된 JSON을 거절한다")
    void assign_failure_invalidResponse(String response) {
        // given
        when(client.complete(any())).thenReturn(response);

        // when & then
        assertThatThrownBy(
                () -> assigner.assign(
                        input,
                        Duration.ofSeconds(10)
                )
        ).hasMessage(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE.getMessage());
    }

    @Test
    @DisplayName("신규 이름은 공통 Unicode 공백 정규화를 적용한다")
    void assign_success_normalizeNewCategory() {
        // given
        when(client.complete(any())).thenReturn("""
                {"existingCategoryId":null,"newName":" 사용자   피드백 ","newDescription":" 사용자 의견과 개선 "}
                """);

        // when
        var decision = assigner.assign(
                input,
                Duration.ofSeconds(10)
        );

        // then
        assertThat(decision.getNewName()).isEqualTo("사용자 피드백");
    }

    @Test
    @DisplayName("완성 문서 제목을 그대로 새 폴더 이름으로 복사한 응답을 거절한다")
    void assign_failure_documentTitleAsCategory() {
        // given
        when(client.complete(any())).thenReturn("""
                {"existingCategoryId":null,"newName":"품질 검증","newDescription":"음성 처리"}
                """);

        // when & then
        assertThatThrownBy(
                () -> assigner.assign(
                        input,
                        Duration.ofSeconds(10)
                )
        ).hasMessage(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE.getMessage());
    }
}
