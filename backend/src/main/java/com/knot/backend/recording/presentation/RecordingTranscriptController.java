package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingTranscriptService;
import com.knot.backend.recording.presentation.dto.response.RecordingTranscriptResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingTranscriptController implements RecordingTranscriptApi {
    private final RecordingTranscriptService recordingTranscriptService;

    @Override
    @GetMapping("/{recordingId}/transcript")
    public RecordingTranscriptResponse findTranscript(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return RecordingTranscriptResponse.from(
                recordingTranscriptService.find(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        recordingId
                )
        );
    }
}
