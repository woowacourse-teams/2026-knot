package com.knot.backend.search.application.notion;

import java.time.Duration;
import java.util.List;

/**
 * Workspace의 노션 연결로 노션 MCP를 부르는 포트. 연결을 찾고 토큰을 복호화하고 갱신하는 일은 구현이 맡는다. 실패는
 * {@link SearchNotionException}의 사유로 알린다. 노션이 인증을 거절하고 갱신도 안 되면 구현이 연결을 만료로 바꾼
 * 뒤 CONNECTION_EXPIRED를 던진다.
 */
public interface SearchNotionClient {

    /**
     * 이름이나 내용으로 페이지와 DB를 찾는다. 노션이 준 순서(관련도) 그대로 돌려준다.
     */
    List<SearchNotionSearchHit> search(
            long workspaceId,
            String query,
            Duration timeout
    );

    /**
     * 페이지 본문을 가져온다. content는 노션 마크다운이다.
     */
    SearchNotionPage fetchPage(
            long workspaceId,
            String pageId,
            Duration timeout
    );

    /**
     * DB의 속성 구조를 가져온다. 돌려준 id는 다시 fetchDatabase와 createPage의 위치로 쓸 수 있다.
     */
    SearchNotionDatabase fetchDatabase(
            long workspaceId,
            String databaseId,
            Duration timeout
    );

    SearchNotionCreatedPage createPage(
            long workspaceId,
            SearchNotionPageCreation creation,
            Duration timeout
    );
}
