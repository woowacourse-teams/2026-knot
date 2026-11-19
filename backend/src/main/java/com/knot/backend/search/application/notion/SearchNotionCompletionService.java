package com.knot.backend.search.application.notion;

import com.knot.backend.search.application.SearchAnswerExecutionService;
import com.knot.backend.search.application.dto.result.SearchAnswerWorkResult;
import com.knot.backend.search.domain.model.notion.SearchNotionEvidence;
import com.knot.backend.search.domain.model.notion.SearchNotionNotice;
import com.knot.backend.search.domain.model.notion.SearchWriteCard;
import com.knot.backend.search.domain.model.notion.SearchWriteCardOrigin;
import com.knot.backend.search.domain.repository.notion.SearchNotionAnswerRepository;
import com.knot.backend.search.domain.repository.notion.SearchWriteCardRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SearchNotionCompletionService {

    private final SearchAnswerExecutionService executions;
    private final SearchNotionAnswerRepository answers;
    private final SearchWriteCardRepository cards;
    private final Clock clock;

    /**
     * 답변 완료와 노션 안내, 노션 근거, 쓰기 확인 카드를 한 트랜잭션에 저장한다. 지금 실행이 아니면(사용자가 멈춤 등) 아무것도 저장하지
     * 않아 카드도 생기지 않는다(SEARCH-R43).
     */
    public boolean complete(
            SearchAnswerWorkResult work,
            String content,
            List<Long> usedDocumentIds,
            Set<Long> candidateIds,
            SearchNotionOutcome outcome
    ) {
        if (!executions.completeGenerated(
                work.answerId(),
                work.token(),
                content,
                usedDocumentIds,
                candidateIds
        )) {
            return false;
        }
        outcome.notice()
                .ifPresent(
                        reason -> answers.save(
                                SearchNotionNotice.create(
                                        work.answerId(),
                                        reason
                                )
                        )
                );
        List<SearchNotionPage> evidences = outcome.evidences();
        for (int index = 0; index < evidences.size(); index++) {
            SearchNotionPage page = evidences.get(index);
            answers.save(
                    SearchNotionEvidence.create(
                            work.answerId(),
                            index + 1,
                            page.title(),
                            page.url()
                    )
            );
        }
        outcome.card()
                .ifPresent(
                        card -> saveCard(
                                work,
                                card
                        )
                );
        return true;
    }

    /**
     * 같은 대화에서 다시 요청해 새 카드를 만들 때 앞선 대기 카드를 취소됨으로 바꾼다(SEARCH-R41). 새 카드가 없는 답변은 앞선
     * 카드를 그대로 둔다(「다시 요청」의 범위는 정본 미정, 기본값).
     */
    private void saveCard(
            SearchAnswerWorkResult work,
            SearchNotionCardDraft card
    ) {
        Instant now = clock.instant();
        cards.findPendingByConversationIdForUpdate(work.conversationId())
                .forEach(pending -> pending.cancel(now));
        cards.save(
                SearchWriteCard.createNotionPage(
                        new SearchWriteCardOrigin(
                                work.answerId(),
                                work.conversationId(),
                                work.workspaceId(),
                                work.memberId()
                        ),
                        card.notionWorkspaceId(),
                        card.draft(),
                        now
                )
        );
    }
}
