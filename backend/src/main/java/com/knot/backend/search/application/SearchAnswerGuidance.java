package com.knot.backend.search.application;

public final class SearchAnswerGuidance {
    public static final String CLARIFY_MEETING = "어느 회의를 말씀하시는지 회의 제목이나 날짜를 알려주세요.";
    public static final String UNAVAILABLE = "질문은 대화에 남겨 두었어요. 지금은 답변을 정리하기 어려워요. 잠시 뒤 다시 질문해 주시면 이어서 도와드릴게요.";
    public static final String RELATED_DOCUMENTS = "관련 기록을 아래에 모았어요. 지금은 내용을 종합하기 어려워요. 문서를 살펴보거나 질문을 조금 더 구체적으로 이어서 해주세요.";

    public static final String RETRIEVAL_UNAVAILABLE = "지금은 회의 기록을 조회하기 어려워 팀의 결정은 확인할 수 없어요. 확인된 기록이 아닌 일반적인 설명으로 이어갈게요.\n\n";
    public static final String NO_SEARCH_MATCHES = "검색한 범위에서 관련 회의 기록을 찾지 못했어요.\n\n";
    public static final String RETRIEVAL_LIMITED = "검색 결과 중 일부 기록을 확인하지 못했어요.\n\n";
    public static final String DOCUMENT_BUDGET_LIMITED = "입력 한도 때문에 일부 기록만 확인했어요. 회의나 기간을 좁히면 더 구체적으로 확인할 수 있어요.";
    public static final String DOCUMENT_BUDGET_EXHAUSTED = "자료가 입력 한도를 넘어 회의 기록을 확인하지 못했어요. 회의나 기간을 좁혀 다시 질문해 주세요.";

    private SearchAnswerGuidance() {}
}
