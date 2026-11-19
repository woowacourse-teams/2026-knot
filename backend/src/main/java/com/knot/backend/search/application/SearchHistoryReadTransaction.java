package com.knot.backend.search.application;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
class SearchHistoryReadTransaction {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T read(Supplier<T> query) {
        return query.get();
    }
}
