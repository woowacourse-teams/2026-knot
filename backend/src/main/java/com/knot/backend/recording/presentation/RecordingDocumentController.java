package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingDocumentService;
import com.knot.backend.recording.presentation.dto.response.RecordingDocumentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingDocumentController implements RecordingDocumentApi {
    private final RecordingDocumentService recordingDocumentService;

    @Override
    @GetMapping("/{recordingId}/document")
    public RecordingDocumentResponse findDocument(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return RecordingDocumentResponse.from(
                recordingDocumentService.find(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        recordingId
                )
        );
    }
}
