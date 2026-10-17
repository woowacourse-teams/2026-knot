package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.application.dto.result.RecordingStartResult;
import com.knot.backend.recording.presentation.dto.request.RecordingStartRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingStartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingStartController implements RecordingStartApi {
    private final RecordingService recordingService;

    @Override
    @PostMapping
    public ResponseEntity<RecordingStartResponse> start(
            @PathVariable long workspaceId,
            @Valid @RequestBody RecordingStartRequest request,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        RecordingStartResult result = recordingService.start(
                workspaceId,
                authenticatedMember.getMemberId(),
                request.toCommand()
        );
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(RecordingStartResponse.from(result));
    }
}
