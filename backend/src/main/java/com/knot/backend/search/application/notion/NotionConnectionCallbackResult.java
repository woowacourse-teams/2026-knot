package com.knot.backend.search.application.notion;

/**
 * 콜백 처리 결과. state를 찾지 못하면 workspaceId가 null이고 결과 값도 없다.
 */
public record NotionConnectionCallbackResult(
        Long workspaceId,
        NotionConnectionOutcome outcome
) {

    public static NotionConnectionCallbackResult unknownState() {
        return new NotionConnectionCallbackResult(
                null,
                null
        );
    }

    public boolean hasWorkspace() {
        return workspaceId != null;
    }
}
