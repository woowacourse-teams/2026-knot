package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingService;
import com.knot.backend.recording.presentation.dto.request.RecordingSpeakerCountRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingSpeakerCountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import static com.knot.backend.global.config.OpenApiConfig.ACCESS_TOKEN_COOKIE;

@SecurityRequirement(name = ACCESS_TOKEN_COOKIE)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
public class RecordingSpeakerCountController {
    private final RecordingService recordings;

    @Operation(summary = "녹음 참가자 수 변경", description = "현재 제어 탭만 RECORDING·PAUSED 동안 수정할 수 있다. speakerCount는 필수이며 null(자동) 또는 정수 1~10. 종료 시 마지막 저장값이 확정된다. 종료 후 409, 제어권 불일치 403.")
    @PatchMapping("/{recordingId}/speaker-count")
    public RecordingSpeakerCountResponse change(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @RequestBody RecordingSpeakerCountRequest request,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        return new RecordingSpeakerCountResponse(
                recordingId,
                recordings.changeSpeakerCount(
                        workspaceId,
                        member.getMemberId(),
                        recordingId,
                        request.toCommand(),
                        request.count()
                )
        );
    }
}
