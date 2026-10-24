package com.knot.backend.document.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.document.application.DocumentTopicCancellationService;
import com.knot.backend.document.domain.model.DocumentTopicOperationStatus;
import com.knot.backend.document.presentation.dto.response.DocumentTopicCancellationResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(prefix = "knot.document-topic-reorganization", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/workspaces/{workspaceId}/document-topics/operations/{operationId}/cancel")
public class DocumentTopicCancellationController implements DocumentTopicCancellationApi {

    private final DocumentTopicCancellationService service;

    @PostMapping
    public ResponseEntity<DocumentTopicCancellationResponse> cancel(
            @PathVariable @Positive long workspaceId,
            @PathVariable @Positive long operationId,
            @AuthenticationPrincipal AuthenticatedMember member
    ) {
        DocumentTopicCancellationResponse response = DocumentTopicCancellationResponse.from(
                service.request(
                        workspaceId,
                        member.getMemberId(),
                        operationId
                )
        );
        HttpStatus status = HttpStatus.ACCEPTED;
        if (response.status() == DocumentTopicOperationStatus.CANCELLED) {
            status = HttpStatus.OK;
        }
        return ResponseEntity.status(status)
                .body(response);
    }
}
