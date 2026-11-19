package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.SearchAnswerGenerator;
import com.knot.backend.search.application.SearchToolScope;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.application.dto.result.SearchDocumentResult;
import com.knot.backend.search.application.dto.result.SearchRetrievalResult;
import com.knot.backend.search.application.dto.result.SearchUsePlan;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 노션 도구가 계획에 있는 답변. 노션 읽기와 위치 검색을 회의 문서 찾기와 같은 시간에 시작하고, 읽기가 모두 끝난 뒤 답글과 초안을 받아
 * 답변 완료와 함께 노션 안내, 노션 근거, 쓰기 확인 카드를 저장한다. 노션에는 쓰지 않는다(SEARCH-R40).
 */
@Component
@RequiredArgsConstructor
public class SearchNotionAnswerFlow {
    private static final ZoneId MEETING_ZONE = ZoneId.of("Asia/Seoul");

    private final SearchNotionReader reader;
    private final ObjectProvider<SearchAnswerGenerator> answers;
    private final ObjectProvider<SearchNotionAnswerGenerator> notionAnswers;
    private final SearchNotionCompletionService completions;

    public SearchNotionReading start(
            SearchAnswerWorkResult work,
            SearchUsePlan plan
    ) {
        return reader.start(
                work.workspaceId(),
                plan
        );
    }

    /**
     * retrievalGuidance는 회의 문서 찾기 결과 안내로 답글 앞에 붙는다. 카드를 만들지 않는 쓰기 요청은 안내 답글만 보낸다.
     */
    public void answer(
            SearchAnswerWorkResult work,
            SearchToolScope scope,
            SearchRetrievalResult retrieval,
            SearchNotionMaterial material,
            String retrievalGuidance
    ) {
        Optional<SearchNotionWriteSkip> skip = material.writeSkip(
                !retrieval.documents()
                        .isEmpty()
        );
        if (skip.isPresent()) {
            completions.complete(
                    work,
                    skip.get()
                            .guidance(),
                    List.of(),
                    Set.of(),
                    SearchNotionOutcome.notice(
                            skip.get()
                                    .notice()
                    )
            );
            return;
        }
        StringBuilder content = new StringBuilder(retrievalGuidance);
        Optional<SearchNotionLocation> location = material.writeLocation();
        if (material.pages()
                .isEmpty() && location.isEmpty()) {
            answerWithoutNotion(
                    work,
                    scope,
                    retrieval,
                    material,
                    content
            );
            return;
        }
        SearchNotionAnswer answer = notionAnswers.getObject()
                .generate(
                        scope,
                        work.question(),
                        retrieval,
                        material.pages(),
                        location.isPresent(),
                        content::append
                );
        answer.issue()
                .ifPresent(
                        issue -> appendGuidance(
                                content,
                                issue.guidance()
                        )
                );
        completions.complete(
                work,
                content.toString(),
                answer.documentIds(),
                candidates(retrieval),
                new SearchNotionOutcome(
                        material.readNotice(),
                        answer.notionEvidences(),
                        card(
                                answer,
                                location,
                                retrieval
                        )
                )
        );
    }

    /**
     * 노션을 읽지 못한 질문은 기존 답변 경로로 회의 문서만으로 답하고 사유만 함께 저장한다(SEARCH-R39).
     */
    private void answerWithoutNotion(
            SearchAnswerWorkResult work,
            SearchToolScope scope,
            SearchRetrievalResult retrieval,
            SearchNotionMaterial material,
            StringBuilder content
    ) {
        List<Long> usedIds = answers.getObject()
                .generateFromRetrieval(
                        scope,
                        work.question(),
                        retrieval,
                        content::append
                );
        completions.complete(
                work,
                content.toString(),
                usedIds,
                candidates(retrieval),
                SearchNotionOutcome.notice(material.readNotice())
        );
    }

    private static void appendGuidance(
            StringBuilder content,
            String guidance
    ) {
        if (!content.isEmpty()) {
            content.append("\n\n");
        }
        content.append(guidance);
    }

    private static Optional<SearchNotionCardDraft> card(
            SearchNotionAnswer answer,
            Optional<SearchNotionLocation> location,
            SearchRetrievalResult retrieval
    ) {
        if (answer.draft()
                .isEmpty() || location.isEmpty()) {
            return Optional.empty();
        }
        SearchNotionAnswer.Draft draft = answer.draft()
                .get();
        SearchDocumentResult source = retrieval.documents()
                .stream()
                .filter(document -> document.documentId() == draft.documentId())
                .findFirst()
                .orElseThrow();
        return Optional.of(
                location.get()
                        .card(
                                draft.title(),
                                draft.body(),
                                source.documentId(),
                                source.title(),
                                meetingDate(source)
                        )
        );
    }

    private static LocalDate meetingDate(SearchDocumentResult document) {
        if (document.recordingStartedAt() == null) {
            return null;
        }
        return LocalDate.ofInstant(
                document.recordingStartedAt(),
                MEETING_ZONE
        );
    }

    private static Set<Long> candidates(SearchRetrievalResult retrieval) {
        return retrieval.documents()
                .stream()
                .map(SearchDocumentResult::documentId)
                .collect(Collectors.toSet());
    }
}
