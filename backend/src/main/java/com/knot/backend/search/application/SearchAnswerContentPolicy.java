package com.knot.backend.search.application;

import com.knot.backend.search.application.dto.result.SearchEvidenceItemResult;
import java.util.List;

public final class SearchAnswerContentPolicy {
    public static final String SOURCE_UNAVAILABLE = "이 답변의 근거를 현재 확인할 수 없어요.";

    private SearchAnswerContentPolicy() {}

    public enum EvidenceStatus {
        AVAILABLE,
        SOURCE_UNAVAILABLE,
        SOURCE_CHANGED,
        CHANGE_UNKNOWN
    }

    public static String content(
            String original,
            EvidenceStatus status
    ) {
        return switch (status) {
            case AVAILABLE -> original;
            case SOURCE_UNAVAILABLE -> SOURCE_UNAVAILABLE;
            case SOURCE_CHANGED -> original + "\n\n근거 문서가 변경되었어요.";
            case CHANGE_UNKNOWN -> original + "\n\n근거 문서의 변경 여부를 확인할 수 없어요.";
        };
    }

    public static List<SearchEvidenceItemResult> evidences(
            List<SearchEvidenceItemResult> items,
            EvidenceStatus status
    ) {
        return status == EvidenceStatus.SOURCE_UNAVAILABLE ? List.of() : items;
    }
}
