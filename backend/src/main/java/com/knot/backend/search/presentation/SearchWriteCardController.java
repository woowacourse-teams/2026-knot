package com.knot.backend.search.presentation;

import com.knot.backend.auth.domain.model.AuthenticatedMember;
import com.knot.backend.search.application.notion.SearchWriteCardExecutionService;
import com.knot.backend.search.application.notion.SearchWriteCardTransitionService;
import com.knot.backend.search.presentation.dto.response.SearchWriteCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/workspaces/{workspaceId}/search/conversations/{conversationId}/write-cards")
public class SearchWriteCardController implements SearchWriteCardApi {

    private final SearchWriteCardExecutionService executions;
    private final SearchWriteCardTransitionService transitions;

    @Override
    @PostMapping(value = "/{cardId}/execute", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchWriteCardResponse execute(
            @PathVariable Long workspaceId,
            @PathVariable Long conversationId,
            @PathVariable Long cardId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return SearchWriteCardResponse.from(
                executions.execute(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        conversationId,
                        cardId
                )
        );
    }

    @Override
    @PostMapping(value = "/{cardId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public SearchWriteCardResponse cancel(
            @PathVariable Long workspaceId,
            @PathVariable Long conversationId,
            @PathVariable Long cardId,
            @AuthenticationPrincipal AuthenticatedMember authenticatedMember
    ) {
        return SearchWriteCardResponse.from(
                transitions.cancel(
                        workspaceId,
                        authenticatedMember.getMemberId(),
                        conversationId,
                        cardId
                )
        );
    }
}
