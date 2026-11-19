package com.knot.backend.search.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.search.application.notion.NotionConnectionService;
import com.knot.backend.search.presentation.dto.request.SearchNotionAuthorizationRequest;
import com.knot.backend.search.presentation.dto.response.SearchNotionAuthorizationResponse;
import com.knot.backend.search.presentation.dto.response.SearchNotionConnectionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SearchNotionConnectionController implements SearchNotionConnectionApi {
    private static final String CONNECTION_PATH = "/api/v1/workspaces/{workspaceId}/search/notion-connection";

    private final NotionConnectionService connections;

    @Override
    @GetMapping(CONNECTION_PATH)
    public SearchNotionConnectionResponse find(
            @PathVariable Long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return SearchNotionConnectionResponse.from(
                connections.find(
                        workspaceId,
                        authenticatedMember.getMemberId()
                )
        );
    }

    @Override
    @PostMapping(value = CONNECTION_PATH + "/authorization", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SearchNotionAuthorizationResponse startAuthorization(
            @PathVariable Long workspaceId,
            @Valid @RequestBody SearchNotionAuthorizationRequest request,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return new SearchNotionAuthorizationResponse(
                connections.startAuthorization(
                        workspaceId,
                        authenticatedMember.getMemberId()
                )
        );
    }

    @Override
    @DeleteMapping(CONNECTION_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(
            @PathVariable Long workspaceId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        connections.disconnect(
                workspaceId,
                authenticatedMember.getMemberId()
        );
    }
}
