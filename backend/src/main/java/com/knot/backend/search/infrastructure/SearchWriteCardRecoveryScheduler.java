package com.knot.backend.search.infrastructure;

import com.knot.backend.global.config.SearchNotionProperties;
import com.knot.backend.global.logging.ServerErrorLogger;
import com.knot.backend.search.application.notion.SearchWriteCardTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 서버가 실행 중에 멈춰 RUNNING으로 남은 쓰기 확인 카드를 무응답 실패로 정리한다. 화면은 RUNNING 카드에 쓰기 요청을 보내지
 * 않고 메시지 조회만 다시 받으므로, 실행 요청이 없어도 카드가 끝 상태로 가게 한다.
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "knot.search.notion", name = "running-card-recovery-enabled", havingValue = "true")
public class SearchWriteCardRecoveryScheduler {

    private final SearchWriteCardTransitionService transitions;
    private final SearchNotionProperties properties;

    @Scheduled(fixedDelayString = "${knot.search.notion.running-card-recovery-interval:PT15S}")
    public void recoverAbandoned() {
        try {
            int recovered = transitions.failAbandoned(properties.runningCardAbandonedAfter());
            if (recovered > 0) {
                log.info(
                        "Search write card recovery outcome=failed count={}",
                        recovered
                );
            }
        } catch (RuntimeException exception) {
            ServerErrorLogger.record(
                    log,
                    "SearchWriteCardRecoveryScheduler phase=recoverAbandoned",
                    exception
            );
        }
    }
}
