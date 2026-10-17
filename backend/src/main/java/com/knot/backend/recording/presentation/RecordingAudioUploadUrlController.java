package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioUploadService;
import com.knot.backend.recording.application.dto.result.RecordingAudioUploadUrlResult;
import com.knot.backend.recording.presentation.dto.request.RecordingAudioUploadUrlRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioUploadUrlResponse;
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
public class RecordingAudioUploadUrlController implements RecordingAudioUploadUrlApi {
    private final RecordingAudioUploadService recordingAudioUploadService;

    @Override
    @PostMapping("/{recordingId}/audio-upload-url")
    public ResponseEntity<RecordingAudioUploadUrlResponse> issue(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @Valid @RequestBody RecordingAudioUploadUrlRequest request,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        RecordingAudioUploadUrlResult result = recordingAudioUploadService.issue(
                workspaceId,
                authenticatedMember.getMemberId(),
                recordingId,
                request.toCommand()
        );
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(RecordingAudioUploadUrlResponse.from(result));
    }
}
