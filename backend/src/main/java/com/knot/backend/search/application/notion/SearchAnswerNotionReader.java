package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.dto.result.SearchAnswerNotionResult;
import com.knot.backend.search.application.dto.result.SearchNotionEvidenceItemResult;
import com.knot.backend.search.application.dto.result.SearchWriteCardResult;
import com.knot.backend.search.domain.model.notion.SearchCreatedNotionPage;
import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 답변에 붙은 노션 근거, 안내 사유, 쓰기 확인 카드를 읽는다. 연결이 끊기거나 바뀌어도 저장된 값을 준다(SEARCH-R37, R42).
 */
@Component
@RequiredArgsConstructor
public class SearchAnswerNotionReader {

    private final SearchNotionAnswerRepository answers;
    private final SearchWriteCardRepository cards;

    /**
     * 한 대화의 답변 ID들에 붙은 값을 종류마다 한 번의 조회로 읽는다.
     */
    public Map<Long, SearchAnswerNotionResult> read(
            long conversationId,
            Collection<Long> answerIds
    ) {
        if (answerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SearchNotionEvidenceItemResult>> evidences = evidences(answerIds);
        Map<Long, SearchNotionNoticeReason> notices = answers.findNotices(answerIds)
                .stream()
                .collect(
                        Collectors.toMap(
                                SearchNotionNotice::getMessageId,
                                SearchNotionNotice::getReason
                        )
                );
        Map<Long, SearchWriteCardResult> writeCards = writeCards(
                conversationId,
                answerIds
        );
        return answerIds.stream()
                .distinct()
                .collect(
                        Collectors.toMap(
                                Function.identity(),
                                answerId -> new SearchAnswerNotionResult(
                                        evidences.getOrDefault(
                                                answerId,
                                                List.of()
                                        ),
                                        notices.get(answerId),
                                        writeCards.get(answerId)
                                )
                        )
                );
    }

    public SearchWriteCardResult card(SearchWriteCard card) {
        List<SearchCreatedNotionPage> history = cards.findCreatedPages(
                card.getConversationId(),
                card.getSourceDocumentId()
        );
        return SearchWriteCardResult.from(
                card,
                history,
                cardTitles(history)
        );
    }

    /**
     * 만든 페이지 이력마다 그 페이지를 만든 카드의 제목을 찾는다.
     */
    private Map<Long, String> cardTitles(List<SearchCreatedNotionPage> history) {
        List<Long> cardIds = history.stream()
                .map(SearchCreatedNotionPage::getWriteCardId)
                .toList();
        return cards.findByIds(cardIds)
                .stream()
                .collect(
                        Collectors.toMap(
                                SearchWriteCard::getId,
                                SearchWriteCard::getTitle
                        )
                );
    }

    private Map<Long, List<SearchNotionEvidenceItemResult>> evidences(Collection<Long> answerIds) {
        return answers.findEvidences(answerIds)
                .stream()
                .collect(
                        Collectors.groupingBy(
                                SearchAnswerNotionReader::messageIdOf,
                                Collectors.mapping(
                                        evidence -> new SearchNotionEvidenceItemResult(
                                                evidence.getTitle(),
                                                evidence.getUrl()
                                        ),
                                        Collectors.toList()
                                )
                        )
                );
    }

    private static long messageIdOf(SearchNotionEvidence evidence) {
        return evidence.getId()
                .messageId();
    }

    private Map<Long, SearchWriteCardResult> writeCards(
            long conversationId,
            Collection<Long> answerIds
    ) {
        List<SearchWriteCard> found = cards.findByAnswerMessageIds(answerIds);
        if (found.isEmpty()) {
            return Map.of();
        }
        List<SearchCreatedNotionPage> created = cards.findCreatedPages(conversationId);
        Map<Long, String> titles = cardTitles(created);
        Map<Long, List<SearchCreatedNotionPage>> history = created.stream()
                .collect(Collectors.groupingBy(SearchCreatedNotionPage::getSourceDocumentId));
        return found.stream()
                .collect(
                        Collectors.toMap(
                                SearchWriteCard::getAnswerMessageId,
                                card -> SearchWriteCardResult.from(
                                        card,
                                        history.getOrDefault(
                                                card.getSourceDocumentId(),
                                                List.of()
                                        ),
                                        titles
                                )
                        )
                );
    }
}
