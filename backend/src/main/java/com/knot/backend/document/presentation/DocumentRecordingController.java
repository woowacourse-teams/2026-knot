package com.knot.backend.document.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentRecordingListService;
import com.knot.backend.document.presentation.dto.response.DocumentRecordingListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-recordings")
@RequiredArgsConstructor
public class DocumentRecordingController implements DocumentRecordingApi {
    private final DocumentRecordingListService documentRecordingListService;

    @Override
    @GetMapping
    public DocumentRecordingListResponse findDocumentRecordings(
            @PathVariable Long workspaceId,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return DocumentRecordingListResponse.from(
                documentRecordingListService.find(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        cursor
                )
        );
    }
}
