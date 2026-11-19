package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.query.SearchDocumentListAction;
import com.knot.backend.search.application.dto.query.SearchDocumentListRequest;
import com.knot.backend.search.application.dto.result.SearchDocumentListResult;
import com.knot.backend.search.infrastructure.llm.SearchDateFilter;
import com.knot.backend.search.infrastructure.persistence.SearchDocumentListPosition;
import com.knot.backend.search.infrastructure.persistence.SearchDocumentListStore;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SearchDocumentListService {
    private static final int PAGE_SIZE = 10;
    private static final String LIST_PREVIEW = "회의 문서 목록을 확인했어요.";
    private static final String NO_LIST = "이전 문서 목록을 이어볼 수 없어요. 새로 문서를 찾아 주세요.";

    private final SearchDocumentListStore lists;

    @Transactional(propagation = Propagation.MANDATORY)
    public String prepareAnswer(
            SearchToolScope scope,
            SearchDocumentListRequest request
    ) {
        return switch (request.action()) {
            case START_BY_MEETING, START_BY_CREATION -> startList(
                    scope,
                    request
            );
            case CONTINUE -> continueList(scope);
            case SELECT -> selectDisplayedItem(
                    scope,
                    request.ordinal()
            );
            case CLARIFY -> "목록에서 몇 번 문서를 열까요? 번호 하나를 알려 주세요.";
        };
    }

    private String startList(
            SearchToolScope scope,
            SearchDocumentListRequest request
    ) {
        String query = request.query()
                .replaceFirst(
                        "(?:^|\\s)최근$",
                        ""
                )
                .strip();
        SearchDateFilter dates = SearchDateFilter.parseMeetingDate(
                query,
                scope.questionAcceptedAt()
        );
        Instant from = null;
        Instant before = null;
        if (dates != null) {
            if (dates.requiresClarification() || dates.needsDateContext()) {
                return "어느 날짜 또는 기간의 회의 문서를 찾을까요?";
            }
            query = dates.query();
            from = dates.from();
            before = dates.before();
        }
        SearchDocumentListPosition position = lists.create(
                scope,
                request.action() == SearchDocumentListAction.START_BY_CREATION,
                query,
                from,
                before
        );
        if (position.itemCount() == 0) {
            return "조건에 맞는 회의 문서를 찾지 못했어요.";
        }
        lists.savePage(
                scope.answerId(),
                position.id(),
                1,
                Math.min(
                        PAGE_SIZE,
                        position.itemCount()
                )
        );
        return LIST_PREVIEW;
    }

    private String continueList(SearchToolScope scope) {
        Optional<SearchDocumentListPosition> latest = lists.latestForUpdate(scope.conversationId());
        if (latest.isEmpty()) {
            return NO_LIST;
        }
        SearchDocumentListPosition position = latest.orElseThrow();
        if (position.displayedThrough() == position.itemCount()) {
            return "목록의 끝이에요.";
        }
        int first = position.displayedThrough() + 1;
        int count = Math.min(
                PAGE_SIZE,
                position.itemCount() - position.displayedThrough()
        );
        lists.savePage(
                scope.answerId(),
                position.id(),
                first,
                position.displayedThrough() + count
        );
        return LIST_PREVIEW;
    }

    private String selectDisplayedItem(
            SearchToolScope scope,
            int ordinal
    ) {
        Optional<SearchDocumentListPosition> latest = lists.latestForUpdate(scope.conversationId());
        if (latest.isEmpty()) {
            return NO_LIST;
        }
        SearchDocumentListPosition position = latest.orElseThrow();
        if (ordinal <= 0 || ordinal > position.displayedThrough()) {
            return "목록에 표시된 문서 번호를 알려 주세요.";
        }
        if (lists.saveSelection(
                scope.answerId(),
                position.id(),
                ordinal
        )) {
            return "목록에서 선택한 문서를 확인했어요.";
        }
        return SearchDocumentListResult.CANNOT_OPEN;
    }
}
