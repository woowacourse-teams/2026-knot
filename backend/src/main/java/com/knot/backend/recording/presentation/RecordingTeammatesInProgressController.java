package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.TeammateRecordingService;
import com.knot.backend.recording.presentation.dto.response.TeammateRecordingListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecordingTeammatesInProgressController implements RecordingTeammatesInProgressApi {
    private final TeammateRecordingService teammateRecordingService;

    @Override
    @GetMapping("/teammates-in-progress")
    public TeammateRecordingListResponse findTeammatesInProgress(
            @PathVariable long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return TeammateRecordingListResponse.from(
                teammateRecordingService.findInProgress(
                        workspaceId,
                        authenticatedMember.getMemberId()
                )
        );
    }
}
