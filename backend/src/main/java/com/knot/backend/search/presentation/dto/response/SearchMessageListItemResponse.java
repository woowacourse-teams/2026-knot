package com.knot.backend.search.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.search.application.dto.result.SearchAnswerNotionResult;
import com.knot.backend.search.application.dto.result.SearchMessageListItemResult;
import com.knot.backend.search.domain.model.SearchMessageRole;
import com.knot.backend.search.domain.model.SearchMessageStatus;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record SearchMessageListItemResponse(
        @Schema(requiredMode = REQUIRED) long id,
        @Schema(requiredMode = REQUIRED) SearchMessageRole role,
        @Schema(minimum = "1", requiredMode = REQUIRED) int sequence,
        @Schema(description = "저장된 전체 본문. STREAMING은 빈 문자열 허용", requiredMode = REQUIRED) String content,
        @Schema(requiredMode = REQUIRED) SearchMessageStatus status,
        @Schema(requiredMode = REQUIRED) Instant createdAt,
        @Schema(description = "Document 카드. rank 오름차순 최대 3개, USER는 빈 배열", requiredMode = REQUIRED) List<SearchEvidenceItemResponse> evidences,
        @Schema(description = "답변에 실제로 쓴 노션 페이지. 없거나 USER면 빈 배열", requiredMode = REQUIRED) List<SearchNotionEvidenceItemResponse> notionEvidences,
        @Schema(description = "노션을 읽지 못해 회의 문서만으로 답한 사유", nullable = true, requiredMode = REQUIRED) SearchNotionNoticeReason notionNotice,
        @Schema(description = "답변에 붙은 쓰기 확인 카드의 지금 상태. 없으면 null", nullable = true, requiredMode = REQUIRED) SearchWriteCardResponse writeCard
) {

    public static SearchMessageListItemResponse from(SearchMessageListItemResult result) {
        SearchAnswerNotionResult notion = result.notion();
        return new SearchMessageListItemResponse(
                result.id(),
                result.role(),
                result.sequence(),
                result.content(),
                result.status(),
                result.createdAt(),
                result.evidences()
                        .stream()
                        .map(SearchEvidenceItemResponse::from)
                        .toList(),
                notion.evidences()
                        .stream()
                        .map(SearchNotionEvidenceItemResponse::from)
                        .toList(),
                notion.notice(),
                writeCard(notion)
        );
    }

    private static SearchWriteCardResponse writeCard(SearchAnswerNotionResult notion) {
        if (notion.writeCard() == null) {
            return null;
        }
        return SearchWriteCardResponse.from(notion.writeCard());
    }
}
