package com.knot.backend.search.infrastructure.llm;

import com.knot.backend.global.config.SearchAgentProperties;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.SearchAnswerGuidance;
import com.knot.backend.search.application.SearchToolAccess;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.notion.SearchNotionAnswer;
import com.knot.backend.search.application.notion.SearchNotionAnswerGenerator;
import com.knot.backend.search.application.notion.SearchNotionPage;
import com.knot.backend.search.application.notion.SearchNotionWriteSkip;
import com.knot.backend.search.domain.SearchAttemptFailure;
import com.knot.backend.search.domain.SearchToolException;
import com.knot.backend.search.infrastructure.persistence.SearchHistoryQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 노션 자료가 있는 답변의 생성기. 답변 AI 호출은 한 번이고 도구 목록을 넘기지 않는다. 노션이 없는 질문은 기존
 * SearchStreamingAnswerGenerator가 그대로 맡는다.
 */
@Slf4j
@RequiredArgsConstructor
public class SearchNotionStreamingAnswerGenerator implements SearchNotionAnswerGenerator {

    private final SearchLlmClient llm;
    private final SearchHistoryQuery history;
    private final SearchToolAccess access;
    private final SearchAgentProperties properties;
    private final ObjectMapper mapper;
    private final Optional<SearchLlmClient> openAiFallback;

    public SearchNotionStreamingAnswerGenerator(
            SearchLlmClient llm,
            SearchHistoryQuery history,
            SearchToolAccess access,
            SearchAgentProperties properties,
            ObjectMapper mapper
    ) {
        this(
                llm,
                history,
                access,
                properties,
                mapper,
                Optional.empty()
        );
    }

    @Override
    public SearchNotionAnswer generate(
            SearchToolScope scope,
            String question,
            SearchRetrievalResult retrieval,
            List<SearchNotionPage> pages,
            boolean draftRequested,
            Consumer<String> onDelta
    ) {
        access.validate(scope);
        List<SearchNotionPage> offered = fitting(
                pages,
                draftRequested
        );
        SearchAnswerBudget budget = new SearchAnswerBudget(
                mapper,
                properties.getContextCharacterLimit() - SearchNotionAnswerPrompt.extraCharacters(
                        mapper,
                        offered,
                        draftRequested
                ),
                question,
                scope.questionAcceptedAt(),
                false,
                retrieval.items()
        );
        try {
            budget.select(
                    retrieval.documents(),
                    history.beforeQuestion(scope)
            );
        } catch (SearchToolException exception) {
            if (retrieval.documents()
                    .isEmpty() || !"SEARCH_CONTEXT_LIMIT_EXCEEDED".equals(exception.getCode())) {
                throw exception;
            }
            access.validate(scope);
            onDelta.accept(SearchAnswerGuidance.DOCUMENT_BUDGET_EXHAUSTED);
            SearchAnswerCoverage.publish(
                    retrieval,
                    List.of(),
                    false,
                    onDelta
            );
            return SearchNotionAnswer.documents(List.of());
        }
        List<Map<String, Object>> messages = SearchNotionAnswerPrompt.messages(
                mapper,
                budget.messages(),
                offered,
                draftRequested
        );
        new SearchAgentContext(
                properties,
                mapper
        ).validate(messages);
        log.info(
                "Search notion answer context workspaceId={} answerId={} notionPages={} offeredNotionPages={} documents={} selectedDocuments={} draftRequested={}",
                scope.workspaceId(),
                scope.answerId(),
                pages.size(),
                offered.size(),
                retrieval.documents()
                        .size(),
                budget.documents()
                        .size(),
                draftRequested
        );
        ValidatedAnswer answer;
        try {
            answer = generateWithFallback(
                    scope,
                    messages,
                    budget.documents(),
                    offered.size(),
                    draftRequested
            );
        } catch (RuntimeException exception) {
            if (ServerErrorLogger.isControlFlow(exception) || SearchAttemptFailure.temporary(exception)
                    || SearchAttemptFailure.terminalNetwork(exception)) {
                throw exception;
            }
            ServerErrorLogger.recordTaskFailure(
                    log,
                    "Search notion answer phase=validation",
                    exception
            );
            access.validate(scope);
            SearchNotionAnswer related = relatedDocuments(
                    budget.documents(),
                    onDelta
            );
            SearchAnswerCoverage.publish(
                    retrieval,
                    List.of(),
                    budget.documentsLimited(),
                    onDelta
            );
            return related;
        }
        onDelta.accept(answer.text());
        SearchAnswerCoverage.publish(
                retrieval,
                answer.response()
                        .usedDocumentIds(),
                budget.documentsLimited(),
                onDelta
        );
        return result(
                answer.response(),
                offered
        );
    }

    /**
     * 기존 답변 생성기와 같게 주 모델이 일시 장애면 검증된 대체 모델을 한 번 부른다. 다시 부르기는 워커가 한 번만 한다.
     */
    private ValidatedAnswer generateWithFallback(
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            List<SearchDocumentResult> documents,
            int notionPages,
            boolean draftRequested
    ) {
        try {
            return validateAttempt(
                    llm,
                    scope,
                    messages,
                    documents,
                    notionPages,
                    draftRequested
            );
        } catch (RuntimeException primary) {
            if (openAiFallback.isEmpty() || !SearchAttemptFailure.temporary(primary)) {
                throw primary;
            }
            access.validate(scope);
            log.info(
                    "Search notion fallback provider=openai answerId={} failureCode={}",
                    scope.answerId(),
                    ServerErrorLogger.safeFailureCode(primary)
            );
            try {
                return validateAttempt(
                        openAiFallback.get(),
                        scope,
                        messages,
                        documents,
                        notionPages,
                        draftRequested
                );
            } catch (RuntimeException fallback) {
                if (ServerErrorLogger.isControlFlow(fallback)) {
                    throw fallback;
                }
                throw SearchAttemptFailure.exhaustedFallback(fallback);
            }
        }
    }

    private ValidatedAnswer validateAttempt(
            SearchLlmClient client,
            SearchToolScope scope,
            List<Map<String, Object>> messages,
            List<SearchDocumentResult> documents,
            int notionPages,
            boolean draftRequested
    ) {
        StringBuilder text = new StringBuilder();
        SearchAnswerJsonStream answer = new SearchAnswerJsonStream(
                mapper,
                text::append
        );
        client.stream(
                scope,
                messages,
                includeArrayLimit -> SearchAnswerSchema.responseFormat(
                        includeArrayLimit,
                        notionPages > 0,
                        draftRequested
                ),
                content -> {
                    access.validate(scope);
                    answer.accept(content);
                }
        );
        access.validate(scope);
        return new ValidatedAnswer(
                text.toString(),
                answer.complete(
                        documents,
                        notionPages,
                        draftRequested
                )
        );
    }

    private record ValidatedAnswer(
            String text,
            SearchAnswerResponse response
    ) {
    }

    /**
     * 노션 페이지 수에 상한은 없지만 답변 입력 한도는 넘을 수 없어, 노션 자료가 한도의 절반을 넘으면 노션이 준 순서의 뒤쪽 페이지부터
     * 뺀다.
     */
    private List<SearchNotionPage> fitting(
            List<SearchNotionPage> pages,
            boolean draftRequested
    ) {
        List<SearchNotionPage> offered = new ArrayList<>(pages);
        int limit = properties.getContextCharacterLimit() / 2;
        while (!offered.isEmpty() && SearchNotionAnswerPrompt.extraCharacters(
                mapper,
                offered,
                draftRequested
        ) > limit) {
            offered.removeLast();
        }
        return List.copyOf(offered);
    }

    private SearchNotionAnswer result(
            SearchAnswerResponse response,
            List<SearchNotionPage> offered
    ) {
        return new SearchNotionAnswer(
                response.usedDocumentIds(),
                response.notionEvidenceIndexes()
                        .stream()
                        .map(offered::get)
                        .toList(),
                Optional.ofNullable(response.notionDraft())
                        .map(
                                draft -> new SearchNotionAnswer.Draft(
                                        draft.title(),
                                        draft.body(),
                                        draft.documentId()
                                )
                        ),
                Optional.ofNullable(response.notionDraftIssue())
                        .map(SearchNotionWriteSkip::valueOf)
        );
    }

    /**
     * 답변을 검증하지 못하면 기존 답변 경로와 같게 관련 회의 문서 이름을 안내한다. 초안과 노션 근거는 만들지 않는다.
     */
    private SearchNotionAnswer relatedDocuments(
            List<SearchDocumentResult> documents,
            Consumer<String> onDelta
    ) {
        List<SearchDocumentResult> related = documents.stream()
                .limit(3)
                .toList();
        if (related.isEmpty()) {
            onDelta.accept(SearchAnswerGuidance.UNAVAILABLE);
            return SearchNotionAnswer.documents(List.of());
        }
        String titles = related.stream()
                .map(document -> "- " + document.title())
                .collect(Collectors.joining("\n"));
        onDelta.accept(SearchAnswerGuidance.RELATED_DOCUMENTS + "\n\n" + titles);
        return SearchNotionAnswer.documents(
                related.stream()
                        .map(SearchDocumentResult::documentId)
                        .toList()
        );
    }
}
