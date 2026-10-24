package com.knot.backend.document.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentTopicRestorationService;
import com.knot.backend.document.application.dto.result.DocumentTopicRestorationResult;
import com.knot.backend.document.presentation.dto.request.DocumentTopicRestorationRequest;
import com.knot.backend.document.presentation.dto.response.DocumentTopicRestorationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
@Validated
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-topics/restorations")
@RequiredArgsConstructor
public class DocumentTopicRestorationController implements DocumentTopicRestorationApi {

    private final DocumentTopicRestorationService service;

    @PostMapping
    public ResponseEntity<DocumentTopicRestorationResponse> start(
            @PathVariable @Positive long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember member,
            @RequestBody @Valid DocumentTopicRestorationRequest request
    ) {
        DocumentTopicRestorationResult result = service.start(
                workspaceId,
                member.getMemberId(),
                UUID.fromString(request.requestId()),
                request.versionId()
        );
        HttpStatus status = HttpStatus.ACCEPTED;
        if (result.replayed()) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status)
                .body(DocumentTopicRestorationResponse.from(result));
    }
}
