package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioFinalizationTransaction;
import com.knot.backend.recording.application.RecordingAudioChunkService;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioSaveResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings/{recordingId}/audio-save/retry")
public class RecordingAudioSaveRetryController implements RecordingAudioSaveRetryApi {
    private final RecordingAudioFinalizationTransaction transaction;
    private final RecordingAudioChunkService chunks;

    @Override
    @PostMapping
    public ResponseEntity<RecordingAudioSaveResponse> retry(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        boolean queued = transaction.retry(
                workspaceId,
                member.getMemberId(),
                recordingId
        );
        return ResponseEntity.status(retryStatus(queued))
                .body(
                        RecordingAudioSaveResponse.from(
                                chunks.findState(
                                        workspaceId,
                                        member.getMemberId(),
                                        recordingId
                                )
                        )
                );
    }
    private HttpStatus retryStatus(boolean queued) {
        if (queued) {
            return HttpStatus.ACCEPTED;
        }
        return HttpStatus.OK;
    }

}
