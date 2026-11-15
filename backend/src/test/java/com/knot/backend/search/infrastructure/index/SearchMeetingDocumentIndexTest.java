package com.knot.backend.search.infrastructure.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchMeetingDocumentIndexTest {

    @TempDir
    private Path directory;

    @Test
    @DisplayName("BM25와 벡터가 모두 찾은 문서를 먼저 반환하고 현재 허용된 문서로 제한한다")
    void find_success_fusesRanksWithinWorkspaceAndAllowedDocuments() throws Exception {
        SearchE5EmbeddingClient embeddings = embeddings();
        SearchMeetingDocumentIndex index = new SearchMeetingDocumentIndex(
                directory,
                embeddings,
                "e5-revision"
        );
        assertThat(
                index.reconcile(
                        1,
                        () -> Map.of(
                                1L,
                                "고객 인터뷰",
                                2L,
                                "금요일 배포 일정",
                                3L,
                                "배포 결정 확정 일정"
                        )
                )
        ).isTrue();
        assertThat(
                index.reconcile(
                        458,
                        () -> Map.of(
                                9L,
                                "배포 결정 확정 일정"
                        )
                )
        ).isTrue();

        assertThat(
                index.find(
                        1,
                        "배포 일정",
                        Set.of(
                                2L,
                                3L,
                                9L
                        ),
                        3
                )
        ).containsExactly(
                2L,
                3L
        );
        assertThat(
                index.find(
                        1,
                        "배포 일정",
                        Set.of(
                                3L,
                                9L
                        ),
                        3
                )
        ).containsExactly(3L);
        assertThat(
                index.find(
                        1,
                        "배포 일정",
                        Set.of(),
                        3
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("같은 문서 버전은 재시작해도 임베딩을 재사용하고 수정·삭제만 반영한다")
    void reconcile_success_reusesVersionAndAppliesChanges() throws Exception {
        SearchMeetingDocumentIndex first = new SearchMeetingDocumentIndex(
                directory,
                embeddings(),
                "e5-revision"
        );
        first.reconcile(
                1,
                () -> Map.of(
                        1L,
                        "고객 인터뷰",
                        2L,
                        "금요일 배포"
                )
        );
        SearchE5EmbeddingClient restartedEmbeddings = embeddings();
        SearchMeetingDocumentIndex restarted = new SearchMeetingDocumentIndex(
                directory,
                restartedEmbeddings,
                "e5-revision"
        );

        assertThat(
                restarted.reconcile(
                        1,
                        () -> Map.of(
                                1L,
                                "고객 인터뷰",
                                2L,
                                "금요일 배포"
                        )
                )
        ).isTrue();
        verify(
                restartedEmbeddings,
                never()
        ).embedPassages(anyList());

        assertThat(
                restarted.reconcile(
                        1,
                        () -> Map.of(
                                2L,
                                "월요일 배포"
                        )
                )
        ).isTrue();
        verify(restartedEmbeddings).embedPassages(List.of("월요일 배포"));
        assertThat(
                restarted.find(
                        1,
                        "배포",
                        Set.of(
                                1L,
                                2L
                        ),
                        10
                )
        ).containsExactly(2L);
    }

    @Test
    @DisplayName("색인 도중 원본 변경 또는 생성 장애가 있으면 기존 스냅샷을 유지하고 다시 처리한다")
    void reconcile_failure_staleOrFailedRunDoesNotPublishPartialChanges() throws Exception {
        SearchE5EmbeddingClient embeddings = embeddings();
        SearchMeetingDocumentIndex index = new SearchMeetingDocumentIndex(
                directory,
                embeddings,
                "e5-revision"
        );
        index.reconcile(
                1,
                () -> Map.of(
                        1L,
                        "첫 배포"
                )
        );
        AtomicInteger reads = new AtomicInteger();
        assertThat(
                index.reconcile(
                        1,
                        () -> {
                            if (reads.getAndIncrement() == 0) {
                                return Map.of(
                                        2L,
                                        "중간 배포"
                                );
                            }
                            return Map.of(
                                    3L,
                                    "최종 배포"
                            );
                        }
                )
        ).isFalse();
        assertThat(
                index.find(
                        1,
                        "배포",
                        Set.of(
                                1L,
                                2L,
                                3L
                        ),
                        10
                )
        ).containsExactly(1L);

        given(embeddings.embedPassages(List.of("실패"))).willThrow(new IllegalStateException("Unavailable"));
        assertThatThrownBy(
                () -> index.reconcile(
                        1,
                        () -> Map.of(
                                4L,
                                "실패"
                        )
                )
        ).isInstanceOf(IllegalStateException.class);
        assertThat(
                index.find(
                        1,
                        "배포",
                        Set.of(
                                1L,
                                2L,
                                3L,
                                4L
                        ),
                        10
                )
        ).containsExactly(1L);
        assertThat(
                index.reconcile(
                        1,
                        () -> Map.of(
                                3L,
                                "최종 배포"
                        )
                )
        ).isTrue();
        assertThat(
                index.find(
                        1,
                        "배포",
                        Set.of(
                                1L,
                                2L,
                                3L
                        ),
                        10
                )
        ).containsExactly(3L);
    }

    private SearchE5EmbeddingClient embeddings() {
        SearchE5EmbeddingClient embeddings = mock(SearchE5EmbeddingClient.class);
        given(embeddings.embedQuery(org.mockito.ArgumentMatchers.anyString())).willReturn(vector(0));
        given(embeddings.embedPassages(anyList())).willAnswer(invocation -> {
            List<String> passages = invocation.getArgument(0);
            return passages.stream()
                    .map(this::passageVector)
                    .toList();
        });
        return embeddings;
    }

    private float[] passageVector(String passage) {
        if (passage.contains("금요일")) {
            return vector(0);
        }
        return vector(1);
    }

    private float[] vector(int position) {
        float[] vector = new float[SearchE5EmbeddingClient.DIMENSIONS];
        vector[position] = 1;
        return vector;
    }
}
