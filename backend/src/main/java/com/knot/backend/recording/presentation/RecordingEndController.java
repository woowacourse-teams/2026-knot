package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingControlService;
import com.knot.backend.recording.application.dto.result.RecordingEndResult;
import com.knot.backend.recording.presentation.dto.request.RecordingEndRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingEndResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingEndController implements RecordingEndApi {
    private final RecordingControlService recordingControlService;

    @Override
    @PostMapping("/{recordingId}/end")
    public RecordingEndResponse end(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @Valid @RequestBody(required = false) RecordingEndRequest request,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        if (request == null) {
            return RecordingEndResponse.from(
                    recordingControlService.end(
                            workspaceId,
                            authenticatedMember.getMemberId(),
                            recordingId,
                            null
                    )
            );
        }
        if (request.audioSeal() == null) {
            return RecordingEndResponse.from(
                    recordingControlService.end(
                            workspaceId,
                            authenticatedMember.getMemberId(),
                            recordingId,
                            request.toCommand()
                    )
            );
        }
        RecordingEndResult result = recordingControlService.end(
                workspaceId,
                authenticatedMember.getMemberId(),
                recordingId,
                request.toCommand(),
                request.toSeal()
        );
        return RecordingEndResponse.from(result);
    }
}
