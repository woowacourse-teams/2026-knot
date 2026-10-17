package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingAudioChunkService;
import com.knot.backend.recording.application.dto.result.RecordingAudioChunkReservationResult;
import com.knot.backend.recording.presentation.dto.request.RecordingAudioChunkRequest;
import com.knot.backend.recording.presentation.dto.request.RecordingControlRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioChunkReservationResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioChunkCompletionResponse;
import com.knot.backend.recording.presentation.dto.response.RecordingAudioSaveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings/{recordingId}")
@RequiredArgsConstructor
public class RecordingAudioChunkController implements RecordingAudioChunkApi {
    private final RecordingAudioChunkService recordingAudioChunkService;

    @Override
    @PostMapping("/audio-chunks")
    public ResponseEntity<RecordingAudioChunkReservationResponse> reserve(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @Valid @RequestBody RecordingAudioChunkRequest request,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        RecordingAudioChunkReservationResult result = recordingAudioChunkService.reserve(
                workspaceId,
                member.getMemberId(),
                recordingId,
                request.toCommand()
        );
        return ResponseEntity.status(reservationStatus(result))
                .body(RecordingAudioChunkReservationResponse.from(result));
    }

    private HttpStatus reservationStatus(RecordingAudioChunkReservationResult result) {
        if (result.created()) {
            return HttpStatus.CREATED;
        }
        return HttpStatus.OK;
    }

    @Override
    @PostMapping("/audio-chunks/{sequence}/complete")
    public ResponseEntity<RecordingAudioChunkCompletionResponse> complete(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @PathVariable int sequence,
            @Valid @RequestBody RecordingControlRequest request,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        return ResponseEntity.ok(
                RecordingAudioChunkCompletionResponse.from(
                        recordingAudioChunkService.complete(
                                workspaceId,
                                member.getMemberId(),
                                recordingId,
                                sequence,
                                request.toCommand()
                        )
                )
        );
    }

    @Override
    @GetMapping("/audio-save")
    public ResponseEntity<RecordingAudioSaveResponse> findState(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        return ResponseEntity.ok(
                RecordingAudioSaveResponse.from(
                        recordingAudioChunkService.findState(
                                workspaceId,
                                member.getMemberId(),
                                recordingId
                        )
                )
        );
    }
}
