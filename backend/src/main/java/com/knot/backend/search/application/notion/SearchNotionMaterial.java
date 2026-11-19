package com.knot.backend.search.application.notion;

import com.knot.backend.search.domain.model.notion.SearchNotionNoticeReason;
import java.util.List;
import java.util.Optional;

/**
 * 답변 AI를 부르기 전에 노션에서 가져온 것. 노션 읽기와 만들기가 계획에 있을 때만 각각 값이 있다.
 */
public record SearchNotionMaterial(
        Optional<SearchNotionReadResult> read,
        Optional<SearchNotionWritePlan> write
) {

    public boolean planned() {
        return read.isPresent() || write.isPresent();
    }

    public List<SearchNotionPage> pages() {
        return read.map(SearchNotionReadResult::pages)
                .orElse(List.of());
    }

    public Optional<SearchNotionNoticeReason> readNotice() {
        return read.flatMap(SearchNotionReadResult::notice);
    }

    /**
     * 위치를 찾았어도 초안의 근거가 될 회의 문서를 찾지 못했으면 초안을 쓰지 않는다.
     */
    public Optional<SearchNotionWriteSkip> writeSkip(boolean meetingFound) {
        if (write.isEmpty()) {
            return Optional.empty();
        }
        Optional<SearchNotionWriteSkip> skip = write.get()
                .skip();
        if (skip.isPresent() || meetingFound) {
            return skip;
        }
        return Optional.of(SearchNotionWriteSkip.MEETING_NOT_FOUND);
    }

    public Optional<SearchNotionLocation> writeLocation() {
        return write.flatMap(SearchNotionWritePlan::location);
    }
}
