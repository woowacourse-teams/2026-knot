package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.presentation.dto.response.RecordingCurrentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingCurrentController implements RecordingCurrentApi {
    private final RecordingService recordingService;

    @Override
    @GetMapping("/current")
    public ResponseEntity<RecordingCurrentResponse> findCurrent(
            @PathVariable long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return recordingService.findCurrent(
                workspaceId,
                authenticatedMember.getMemberId()
        )
                .map(result -> ResponseEntity.ok(RecordingCurrentResponse.from(result)))
                .orElseGet(
                        () -> ResponseEntity.noContent()
                                .build()
                );
    }
}
