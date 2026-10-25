package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.RecentRecordingListService;
import com.knot.backend.recording.application.dto.query.RecentRecordingListParameters;
import com.knot.backend.recording.presentation.dto.response.RecentRecordingListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class RecentRecordingController implements RecentRecordingApi {
    private final RecentRecordingListService recentRecordingListService;

    @Override
    @GetMapping
    public RecentRecordingListResponse findRecentRecordings(
            @PathVariable long workspaceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return RecentRecordingListResponse.from(
                recentRecordingListService.find(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        RecentRecordingListParameters.of(
                                cursor,
                                size
                        )
                )
        );
    }
}
