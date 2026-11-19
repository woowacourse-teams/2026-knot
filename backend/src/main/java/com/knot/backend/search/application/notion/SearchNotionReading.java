package com.knot.backend.search.application.notion;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 회의 문서 찾기와 같은 시간에 진행 중인 노션 읽기와 위치 검색. 각 작업은 시간 한도가 지나면 실패 결과로 끝난다.
 */
public record SearchNotionReading(
        Optional<CompletableFuture<SearchNotionReadResult>> read,
        Optional<CompletableFuture<SearchNotionWritePlan>> write
) {

    public static SearchNotionReading none() {
        return new SearchNotionReading(
                Optional.empty(),
                Optional.empty()
        );
    }

    public SearchNotionMaterial await() {
        return new SearchNotionMaterial(
                read.map(CompletableFuture::join),
                write.map(CompletableFuture::join)
        );
    }
}
