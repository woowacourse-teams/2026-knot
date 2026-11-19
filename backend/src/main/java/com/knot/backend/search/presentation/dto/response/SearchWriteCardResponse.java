package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.model.notion.SearchWriteCardDatabaseField;
import com.knot.backend.search.domain.model.notion.SearchWriteCardFailureReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardLocationKind;
import com.knot.backend.search.domain.model.notion.SearchWriteCardRetryReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCardStatus;
import com.knot.backend.search.domain.model.notion.SearchWriteCardType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "쓰기 확인 카드. 노션 페이지 ID와 연결 정보 같은 내부 값은 싣지 않는다")
public record SearchWriteCardResponse(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) SearchWriteCardType type,
        @Schema(requiredMode = REQUIRED) SearchWriteCardStatus status,
        @Schema(requiredMode = REQUIRED) WriteCardLocation location,
        @Schema(description = "만들 페이지 제목", requiredMode = REQUIRED) String title,
        @Schema(description = "만들 페이지 본문 전체", requiredMode = REQUIRED) String body,
        @Schema(description = "DB 아래에 만들 때 채워질 속성과 값. 일반 페이지 아래면 null", nullable = true, requiredMode = REQUIRED) List<WriteCardDatabaseField> databaseFields,
        @Schema(requiredMode = REQUIRED) WriteCardSourceDocument sourceDocument,
        @Schema(description = "실행 요청을 받는 카드인지. DB 속성을 확인하지 못한 카드는 false", requiredMode = REQUIRED) boolean executable,
        @Schema(description = "만든 노션 페이지 주소. DONE일 때만 값이 있다", nullable = true, requiredMode = REQUIRED) String createdPageUrl,
        @Schema(description = "다시 누를 수 있는 오류로 대기로 돌아온 사유", nullable = true, requiredMode = REQUIRED) SearchWriteCardRetryReason retryReason,
        @Schema(description = "FAILED일 때 이유", nullable = true, requiredMode = REQUIRED) SearchWriteCardFailureReason failureReason,
        @Schema(description = "같은 대화에서 같은 회의 문서로 이미 만든 페이지. 최근 것부터, 카드 자신이 만든 페이지는 빼고", requiredMode = REQUIRED) List<WriteCardExistingPage> existingPages
) {

    public static SearchWriteCardResponse from(SearchWriteCardResult result) {
        return new SearchWriteCardResponse(
                result.id(),
                result.type(),
                result.status(),
                new WriteCardLocation(
                        result.locationName(),
                        result.locationPath(),
                        result.locationKind()
                ),
                result.title(),
                result.body(),
                databaseFields(result.databaseFields()),
                new WriteCardSourceDocument(
                        result.sourceDocumentId(),
                        result.sourceDocumentTitle()
                ),
                result.executable(),
                result.createdPageUrl(),
                result.retryReason(),
                result.failureReason(),
                result.existingPages()
                        .stream()
                        .map(
                                page -> new WriteCardExistingPage(
                                        page.url(),
                                        page.createdAt()
                                )
                        )
                        .toList()
        );
    }

    private static List<WriteCardDatabaseField> databaseFields(List<SearchWriteCardDatabaseField> fields) {
        if (fields == null) {
            return null;
        }
        return fields.stream()
                .map(
                        field -> new WriteCardDatabaseField(
                                field.name(),
                                field.value()
                        )
                )
                .toList();
    }

    public record WriteCardLocation(
            @Schema(description = "상위 페이지나 DB의 이름", requiredMode = REQUIRED) String name,
            @Schema(description = "위치 경로 한 줄", requiredMode = REQUIRED) String path,
            @Schema(requiredMode = REQUIRED) SearchWriteCardLocationKind kind
    ) {
    }

    public record WriteCardDatabaseField(
            @Schema(description = "속성 이름", requiredMode = REQUIRED) String name,
            @Schema(description = "채워질 값", requiredMode = REQUIRED) String value
    ) {
    }

    public record WriteCardSourceDocument(
            @Schema(requiredMode = REQUIRED) long documentId,
            @Schema(requiredMode = REQUIRED) String title
    ) {
    }

    public record WriteCardExistingPage(
            @Schema(description = "만든 노션 페이지 주소", requiredMode = REQUIRED) String url,
            @Schema(requiredMode = REQUIRED) Instant createdAt
    ) {
    }
}
