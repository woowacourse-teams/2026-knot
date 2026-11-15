package com.knot.backend.search.presentation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SearchSseTimingTest {

    @Test
    @DisplayName("진행 이벤트와 공백을 건너뛰고 첫 본문과 종료 시간을 따로 기록한다")
    void readToEnd_success_timesFirstBodyAndTerminal() throws Exception {
        AtomicLong clock = new AtomicLong();
        SearchSseTiming timing = new SearchSseTiming(
                new JsonMapper(),
                clock::getAndIncrement
        );
        try (BufferedReader stream = reader("""
                event:accepted
                data:{}

                event:progress
                data:{}

                event:delta
                data:{"text":"  "}

                event:delta
                data:{"text":"첫 답변"}

                event:delta
                data:{"text":"추가 답변"}

                event:completed
                data:{}

                """)) {
            timing.readToEnd(stream);
        }
        assertThat(timing.firstDelta()).contains(Duration.ofNanos(4));
        assertThat(timing.terminalTime()).contains(Duration.ofNanos(6));
        assertThat(timing.terminalEvent()).contains("completed");
    }

    @Test
    @DisplayName("재접속 스냅샷과 실패 이벤트를 답변 본문 Delta로 집계하지 않는다")
    void readToEnd_success_keepsFirstDeltaAbsentForSnapshotAndFailure() throws Exception {
        SearchSseTiming timing = new SearchSseTiming(new JsonMapper());
        try (BufferedReader stream = reader("""
                event:snapshot
                data:{"content":"저장된 답변"}

                event:failed
                data:{}

                """)) {
            timing.readToEnd(stream);
        }
        assertThat(timing.firstDelta()).isEmpty();
        assertThat(timing.terminalTime()).isPresent();
        assertThat(timing.terminalEvent()).contains("failed");
    }

    @Test
    @DisplayName("연결 종료만으로 완료를 판정하지 않는다")
    void readToEnd_success_keepsTerminalAbsentForDisconnect() throws Exception {
        SearchSseTiming timing = new SearchSseTiming(new JsonMapper());
        try (BufferedReader stream = reader("""
                event:delta
                data:{"text":"본문"}

                event:completed
                data:{}
                """)) {
            timing.readToEnd(stream);
        }
        assertThat(timing.firstDelta()).isPresent();
        assertThat(timing.terminalTime()).isEmpty();
        assertThat(timing.terminalEvent()).isEmpty();
    }

    private BufferedReader reader(String events) {
        return new BufferedReader(new StringReader(events));
    }
}
