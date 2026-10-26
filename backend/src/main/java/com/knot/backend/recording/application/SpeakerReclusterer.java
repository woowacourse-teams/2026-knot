package com.knot.backend.recording.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * 녹음 전체의 화자 근거 조각을 한 번에 다시 묶는다. 2026-10 구간 전사 실험의 adaptive
 * 재군집(cluster_variants_v2.ts, cluster.ts)을 같은 결과가 나오도록 옮겼다. 특징값은 실험처럼 float32로
 * 저장하고 계산은 double로 한다.
 */
final class SpeakerReclusterer {
    /** 실제 화자 수가 아니라 과분할 상한이다. 조각 4개마다 한 군집까지 허용한다. */
    private static final int MAX_CLUSTERS = 12;
    private static final int MIN_CLUSTERS = 2;
    private static final int OBSERVATIONS_PER_CLUSTER = 4;
    /** 중심 코사인이 이 이상인 군집은 같은 화자로 합친다 */
    private static final double MERGE_MIN_COSINE = .75;
    private static final int RESTARTS = 10;
    private static final int MAX_ITERATIONS = 100;
    private static final int SEED = 20260925;
    private static final double UINT32_RANGE = 4294967296.0;

    private SpeakerReclusterer() {}

    /** 조각마다 0부터 등장 순서로 매긴 화자 번호를 돌려준다. */
    static int[] labels(List<float[]> embeddings) {
        if (embeddings.isEmpty()) {
            return new int[0];
        }
        List<float[]> vectors = embeddings.stream()
                .map(SpeakerReclusterer::l2Normalize)
                .map(SpeakerReclusterer::l2Normalize)
                .toList();
        int clusters = Math.min(
                vectors.size(),
                Math.max(
                        MIN_CLUSTERS,
                        Math.min(
                                MAX_CLUSTERS,
                                (vectors.size() + OBSERVATIONS_PER_CLUSTER - 1) / OBSERVATIONS_PER_CLUSTER
                        )
                )
        );
        return compact(
                mergeClose(
                        vectors,
                        kMeans(
                                vectors,
                                clusters
                        )
                )
        );
    }

    static float[] l2Normalize(float[] vector) {
        double sumSquares = 0;
        for (float component : vector) {
            sumSquares += (double) component * component;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm == 0) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / norm);
        }
        return normalized;
    }

    private static int[] kMeans(
            List<float[]> vectors,
            int k
    ) {
        int clusters = Math.max(
                1,
                Math.min(
                        k,
                        vectors.size()
                )
        );
        if (clusters == 1) {
            return new int[vectors.size()];
        }
        DoubleSupplier random = mulberry32(SEED);
        int[] best = null;
        double bestInertia = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < RESTARTS; attempt++) {
            Run run = runOnce(
                    vectors,
                    clusters,
                    random
            );
            if (best == null || run.inertia() < bestInertia) {
                best = run.labels();
                bestInertia = run.inertia();
            }
        }
        return best;
    }

    private static Run runOnce(
            List<float[]> vectors,
            int k,
            DoubleSupplier random
    ) {
        List<float[]> centroids = initialCentroids(
                vectors,
                k,
                random
        );
        int[] labels = nearest(
                vectors,
                centroids
        );
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            centroids = recomputed(
                    vectors,
                    labels,
                    centroids
            );
            int[] next = nearest(
                    vectors,
                    centroids
            );
            boolean converged = Arrays.equals(
                    next,
                    labels
            );
            labels = next;
            if (converged) {
                break;
            }
        }
        double inertia = 0;
        for (int index = 0; index < vectors.size(); index++) {
            inertia += squaredDistance(
                    vectors.get(index),
                    centroids.get(labels[index])
            );
        }
        return new Run(
                labels,
                inertia
        );
    }

    /** k-means++: 이미 고른 중심에서 먼 조각일수록 다음 중심으로 뽑힐 확률이 높다. */
    private static List<float[]> initialCentroids(
            List<float[]> vectors,
            int k,
            DoubleSupplier random
    ) {
        List<float[]> centroids = new ArrayList<>();
        centroids.add(
                vectors.get((int) Math.floor(random.getAsDouble() * vectors.size()))
                        .clone()
        );
        double[] nearestSquared = new double[vectors.size()];
        for (int index = 0; index < vectors.size(); index++) {
            nearestSquared[index] = squaredDistance(
                    vectors.get(index),
                    centroids.getFirst()
            );
        }
        while (centroids.size() < k) {
            float[] centroid = vectors.get(
                    pick(
                            nearestSquared,
                            random
                    )
            )
                    .clone();
            centroids.add(centroid);
            for (int index = 0; index < vectors.size(); index++) {
                nearestSquared[index] = Math.min(
                        nearestSquared[index],
                        squaredDistance(
                                vectors.get(index),
                                centroid
                        )
                );
            }
        }
        return centroids;
    }

    private static int pick(
            double[] nearestSquared,
            DoubleSupplier random
    ) {
        double total = 0;
        for (double value : nearestSquared) {
            total += value;
        }
        if (total <= 0) {
            return nearestSquared.length - 1;
        }
        double threshold = random.getAsDouble() * total;
        for (int index = 0; index < nearestSquared.length; index++) {
            threshold -= nearestSquared[index];
            if (threshold <= 0) {
                return index;
            }
        }
        return nearestSquared.length - 1;
    }

    private static int[] nearest(
            List<float[]> vectors,
            List<float[]> centroids
    ) {
        int[] labels = new int[vectors.size()];
        for (int index = 0; index < vectors.size(); index++) {
            double best = Double.POSITIVE_INFINITY;
            for (int label = 0; label < centroids.size(); label++) {
                double distance = squaredDistance(
                        vectors.get(index),
                        centroids.get(label)
                );
                if (distance < best) {
                    best = distance;
                    labels[index] = label;
                }
            }
        }
        return labels;
    }

    /** 군집별 평균. 빈 군집은 자기 중심에서 가장 먼 조각으로 다시 심는다. */
    private static List<float[]> recomputed(
            List<float[]> vectors,
            int[] labels,
            List<float[]> centroids
    ) {
        int dimension = vectors.getFirst().length;
        double[][] sums = new double[centroids.size()][dimension];
        int[] counts = new int[centroids.size()];
        for (int index = 0; index < vectors.size(); index++) {
            counts[labels[index]]++;
            for (int d = 0; d < dimension; d++) {
                sums[labels[index]][d] += vectors.get(index)[d];
            }
        }
        List<float[]> next = new ArrayList<>();
        for (int label = 0; label < centroids.size(); label++) {
            if (counts[label] == 0) {
                next.add(
                        vectors.get(
                                farthest(
                                        vectors,
                                        labels,
                                        centroids
                                )
                        )
                                .clone()
                );
                continue;
            }
            float[] centroid = new float[dimension];
            for (int d = 0; d < dimension; d++) {
                centroid[d] = (float) (sums[label][d] / counts[label]);
            }
            next.add(centroid);
        }
        return next;
    }

    private static int farthest(
            List<float[]> vectors,
            int[] labels,
            List<float[]> centroids
    ) {
        int farthest = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < vectors.size(); index++) {
            double distance = squaredDistance(
                    vectors.get(index),
                    centroids.get(labels[index])
            );
            if (distance > best) {
                best = distance;
                farthest = index;
            }
        }
        return farthest;
    }

    /** k를 실제보다 크게 잡아 쪼개진 같은 화자를 중심 코사인이 가장 큰 쌍부터 되돌린다. */
    private static int[] mergeClose(
            List<float[]> vectors,
            int[] labels
    ) {
        int[] current = labels.clone();
        while (true) {
            Map<Integer, float[]> centroids = centroids(
                    vectors,
                    current
            );
            List<Integer> order = List.copyOf(centroids.keySet());
            if (order.size() < 2) {
                return current;
            }
            int from = -1;
            int into = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < order.size(); i++) {
                for (int j = i + 1; j < order.size(); j++) {
                    double cosine = dot(
                            centroids.get(order.get(i)),
                            centroids.get(order.get(j))
                    );
                    if (cosine > best) {
                        best = cosine;
                        from = order.get(j);
                        into = order.get(i);
                    }
                }
            }
            if (best < MERGE_MIN_COSINE) {
                return current;
            }
            for (int index = 0; index < current.length; index++) {
                if (current[index] == from) {
                    current[index] = into;
                }
            }
        }
    }

    /** 처음 등장한 순서의 군집별 정규화 중심. 합은 실험처럼 float32로 누적한다. */
    private static Map<Integer, float[]> centroids(
            List<float[]> vectors,
            int[] labels
    ) {
        Map<Integer, float[]> sums = new LinkedHashMap<>();
        for (int index = 0; index < labels.length; index++) {
            float[] sum = sums.computeIfAbsent(
                    labels[index],
                    label -> new float[vectors.getFirst().length]
            );
            float[] vector = vectors.get(index);
            for (int d = 0; d < sum.length; d++) {
                sum[d] = (float) ((double) sum[d] + vector[d]);
            }
        }
        Map<Integer, float[]> centroids = new LinkedHashMap<>();
        sums.forEach(
                (
                        label,
                        sum
                ) -> centroids.put(
                        label,
                        l2Normalize(sum)
                )
        );
        return centroids;
    }

    private static int[] compact(int[] labels) {
        Map<Integer, Integer> order = new LinkedHashMap<>();
        int[] compacted = new int[labels.length];
        for (int index = 0; index < labels.length; index++) {
            compacted[index] = order.computeIfAbsent(
                    labels[index],
                    label -> order.size()
            );
        }
        return compacted;
    }

    private static double squaredDistance(
            float[] a,
            float[] b
    ) {
        double sum = 0;
        for (int index = 0; index < a.length; index++) {
            double difference = (double) a[index] - b[index];
            sum += difference * difference;
        }
        return sum;
    }

    private static double dot(
            float[] a,
            float[] b
    ) {
        double sum = 0;
        for (int index = 0; index < a.length; index++) {
            sum += (double) a[index] * b[index];
        }
        return sum;
    }

    /** mulberry32. 같은 시드에서 실험과 같은 수열을 낸다. */
    private static DoubleSupplier mulberry32(int seed) {
        int[] state = {seed};
        return () -> {
            state[0] += 0x6d2b79f5;
            int t = state[0];
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / UINT32_RANGE;
        };
    }

    private record Run(
            int[] labels,
            double inertia
    ) {
    }
}
