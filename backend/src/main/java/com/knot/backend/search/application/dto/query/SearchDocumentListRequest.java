package com.knot.backend.search.application.dto.query;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SearchDocumentListRequest(
        SearchDocumentListAction action,
        String query,
        int ordinal
) {
    private static final Pattern START = Pattern.compile(
            "(?:(?<scope>[^\\n,;]{1,100}?)\\s+)?(?:(?<creation>가장\\s*최근|최근에\\s*만든)\\s*문서|(?:최근\\s*)?회의\\s*(?:문서|기록)|문서\\s*목록)(?:\\s*목록)?(?:을|를)?\\s*(?:보여\\s*(?:줘|주세요)|찾아\\s*(?:줘|주세요)|알려\\s*(?:줘|주세요))?"
    );
    private static final Pattern NEXT = Pattern
            .compile("(?:다음(?:\\s*(?:문서|목록|페이지))?\\s*)?(?:더\\s*)?보여\\s*(?:줘|주세요)|더\\s*보기");
    private static final Pattern SELECT = Pattern
            .compile("([0-9]{1,9})\\s*번(?:째)?(?:\\s*문서)?(?:을|를)?\\s*(?:열어|보여)\\s*(?:줘|주세요)");
    private static final Pattern AMBIGUOUS = Pattern.compile("[0-9]+\\s*번(?:이나|과|와|\\s*또는|\\s*말고)\\s*[0-9]+\\s*번.*");

    public static Optional<SearchDocumentListRequest> parse(String question) {
        String text = question.strip()
                .replaceAll(
                        "[.!?？]+$",
                        ""
                )
                .strip();
        if (NEXT.matcher(text)
                .matches() && !text.matches("보여\\s*(?:줘|주세요)")) {
            return Optional.of(
                    new SearchDocumentListRequest(
                            SearchDocumentListAction.CONTINUE,
                            "",
                            0
                    )
            );
        }
        Matcher selection = SELECT.matcher(text);
        if (selection.matches()) {
            return Optional.of(
                    new SearchDocumentListRequest(
                            SearchDocumentListAction.SELECT,
                            "",
                            Integer.parseInt(selection.group(1))
                    )
            );
        }
        if (AMBIGUOUS.matcher(text)
                .matches()) {
            return Optional.of(
                    new SearchDocumentListRequest(
                            SearchDocumentListAction.CLARIFY,
                            "",
                            0
                    )
            );
        }
        Matcher start = START.matcher(text);
        if (!start.matches()) {
            return Optional.empty();
        }
        String scope = start.group("scope");
        if (scope == null) {
            scope = "";
        }
        if (scope.matches(".*(?:요약|정리|비교|답변|그리고|알려|찾아|무슨|두 번째).*")) {
            return Optional.empty();
        }
        if (start.group("creation") != null) {
            return Optional.of(
                    new SearchDocumentListRequest(
                            SearchDocumentListAction.START_BY_CREATION,
                            scope,
                            0
                    )
            );
        }
        return Optional.of(
                new SearchDocumentListRequest(
                        SearchDocumentListAction.START_BY_MEETING,
                        scope,
                        0
                )
        );
    }
}
