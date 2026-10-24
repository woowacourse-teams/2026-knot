package com.knot.backend.document.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentTopicStatusService;
import com.knot.backend.document.presentation.dto.response.DocumentTopicStatusResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-topics/status")
public class DocumentTopicStatusController implements DocumentTopicStatusApi {

    private final DocumentTopicStatusService service;

    @GetMapping
    public ResponseEntity<DocumentTopicStatusResponse> find(
            @PathVariable @Positive long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        DocumentTopicStatusResponse response = DocumentTopicStatusResponse.from(
                service.find(
                        workspaceId,
                        member.getMemberId()
                )
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }
}
