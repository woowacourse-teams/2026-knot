package com.knot.backend.search.infrastructure.notion;

import com.knot.backend.search.application.notion.SearchNotionFailure;
import java.util.Set;

/**
 * 도구를 부른 목적. 같은 노션 오류 코드도 읽기와 쓰기에서 다른 사유로 바꾼다.
 */
enum NotionToolAccess {
    SEARCH,
    READ,
    WRITE;

    private static final Set<String> NO_ACCESS = Set.of(
            "restricted_resource",
            "object_not_found"
    );

    /**
     * create의 validation_error를 CONTENT_TOO_LONG으로 보는 것은 가정이다(노션 문서가 본문이 너무 클 때 이
     * 코드를 준다고만 적었다).
     */
    SearchNotionFailure failure(String code) {
        if (this == READ && NO_ACCESS.contains(code)) {
            return SearchNotionFailure.NOT_FOUND;
        }
        if (this == WRITE && NO_ACCESS.contains(code)) {
            return SearchNotionFailure.PERMISSION_DENIED;
        }
        if (this == WRITE && "validation_error".equals(code)) {
            return SearchNotionFailure.CONTENT_TOO_LONG;
        }
        return SearchNotionFailure.FAILED;
    }
}
