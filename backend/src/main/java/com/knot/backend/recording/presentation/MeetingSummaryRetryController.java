package com.knot.backend.recording.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.recording.application.MeetingSummaryRetryService;
import com.knot.backend.recording.presentation.dto.response.MeetingSummaryRetryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recordings")
@RequiredArgsConstructor
public class MeetingSummaryRetryController implements MeetingSummaryRetryApi {

    private final MeetingSummaryRetryService service;

    @Override
    @PostMapping("/{recordingId}/meeting-summary/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MeetingSummaryRetryResponse retry(
            @PathVariable long workspaceId,
            @PathVariable long recordingId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return MeetingSummaryRetryResponse.from(
                service.retry(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        recordingId
                )
        );
    }
}
