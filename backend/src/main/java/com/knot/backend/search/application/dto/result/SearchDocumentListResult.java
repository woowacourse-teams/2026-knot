package com.knot.backend.search.application.dto.result;

import java.util.List;
import java.util.stream.Collectors;

public record SearchDocumentListResult(
        boolean selection,
        boolean hasMore,
        List<SearchDocumentSelection> items
) {
    public static final String CANNOT_OPEN = "현재 이 문서를 열 수 없어요.";

    public String content() {
        if (selection) {
            if (items.isEmpty()) {
                return CANNOT_OPEN;
            }
            return "“" + items.getFirst()
                    .title() + "” 문서를 선택했어요.";
        }
        if (items.isEmpty() && hasMore) {
            return "이 구간에서 현재 볼 수 있는 문서가 없어요. 더 보여달라고 하면 다음 번호부터 확인할 수 있어요.";
        }
        if (items.isEmpty()) {
            return "이 구간에서 현재 볼 수 있는 문서가 없어요. 목록의 끝이에요.";
        }
        String content = items.stream()
                .map(item -> item.ordinal() + ". " + item.title())
                .collect(Collectors.joining("\n"));
        if (hasMore) {
            return content + "\n\n더 보여달라고 하면 다음 번호부터 이어서 볼 수 있어요.";
        }
        return content + "\n\n목록의 끝이에요.";
    }

    public SearchDocumentSelection selectedDocument() {
        if (selection && !items.isEmpty()) {
            return items.getFirst();
        }
        return null;
    }
}
