package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioPlaybackService;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioPlaybackResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingAudioPlaybackController implements RecordingAudioPlaybackApi {
    private final RecordingAudioPlaybackService recordingAudioPlaybackService;

    @Override
    @GetMapping("/{recordingId}/audio-playback")
    public ResponseEntity<RecordingAudioPlaybackResponse> findPlayback(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        RecordingAudioPlaybackResponse response = RecordingAudioPlaybackResponse.from(
                recordingAudioPlaybackService.find(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        recordingId
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
