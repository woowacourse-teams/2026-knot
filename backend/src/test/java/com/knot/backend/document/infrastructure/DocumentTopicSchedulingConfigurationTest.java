package com.knot.backend.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.knot.backend.document.application.DocumentTopicCancellationService;
import com.knot.backend.document.application.DocumentTopicWorker;
import com.knot.backend.document.domain.repository.DocumentTopicOperationRepository;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class DocumentTopicSchedulingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DocumentTopicScheduler.class)
            .withBean(
                    DocumentTopicOperationRepository.class,
                    () -> mock(DocumentTopicOperationRepository.class)
            )
            .withBean(
                    DocumentTopicWorker.class,
                    () -> mock(DocumentTopicWorker.class)
            )
            .withBean(
                    DocumentTopicCancellationService.class,
                    () -> mock(DocumentTopicCancellationService.class)
            )
            .withBean(
                    "documentTopicExecutor",
                    TaskExecutor.class,
                    () -> mock(TaskExecutor.class)
            )
            .withBean(
                    Clock.class,
                    Clock::systemUTC
            );

    @Test
    @DisplayName("다른 워커를 켜지 않아도 주제 정리의 스케줄을 실제 등록한다")
    void scheduling_success_independent() {
        // when & then
        runner.withPropertyValues("knot.document-topic-reorganization.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                    ScheduledAnnotationBeanPostProcessor processor = context
                            .getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(processor.getScheduledTasks()).hasSize(1);
                });
    }

    @Test
    @DisplayName("주제 정리가 비활성 상태면 스케줄을 등록하지 않는다")
    void scheduling_success_disabled() {
        // when & then
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DocumentTopicScheduler.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
