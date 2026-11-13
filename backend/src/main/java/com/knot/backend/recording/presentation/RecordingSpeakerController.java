package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecordingSpeakerService;
import com.knot.backend.recording.presentation.dto.request.RecordingSpeakerNameRequest;
import com.knot.backend.recording.presentation.dto.response.RecordingSpeakerNameResponse;
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
public class RecordingSpeakerController {
    private final RecordingSpeakerService speakers;

    @Operation(summary = "화자 표시 이름 변경", description = "현재 워크스페이스 멤버가 해당 녹음의 같은 화자 표시 이름을 모두 바꾼다. 공백 제거 후 1~50 코드 포인트, 제어문자 금지. 요약·문서 내용은 바꾸지 않는다. 없는 화자는 404.")
    @PatchMapping("/{recordingId}/speakers/{speakerNumber}")
    public RecordingSpeakerNameResponse rename(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @PathVariable int speakerNumber,
            @RequestBody RecordingSpeakerNameRequest request,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        return new RecordingSpeakerNameResponse(
                recordingId,
                speakerNumber,
                speakers.rename(
                        workspaceId,
                        member.getMemberId(),
                        recordingId,
                        speakerNumber,
                        request.name()
                )
        );
    }
}
