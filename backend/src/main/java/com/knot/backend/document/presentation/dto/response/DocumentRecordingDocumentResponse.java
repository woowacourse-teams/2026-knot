package com.knot.backend.document.presentation.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.knot.backend.document.application.dto.result.DocumentRecordingDocumentResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record DocumentRecordingDocumentResponse(
        @Schema(description = "Document ID. 문서 상세 조회에 쓴다", example = "301", requiredMode = REQUIRED) long id,
        @Schema(description = "읽기 전용 제목. 문서 카드의 title과 같다", example = "탈퇴 데이터 보존 범위", requiredMode = REQUIRED) String title,
        @Schema(description = "AI 요약. 문서 카드의 summary와 같고 없으면 null", example = "탈퇴 후 30일간 데이터를 보관하기로 했다.", nullable = true, requiredMode = REQUIRED) String summary
) {

    public static DocumentRecordingDocumentResponse from(DocumentRecordingDocumentResult result) {
        return new DocumentRecordingDocumentResponse(
                result.id(),
                result.title(),
                result.summary()
        );
    }
}
