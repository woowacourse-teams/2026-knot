package com.knot.backend.recording.application;

import com.knot.backend.recording.application.dto.result.AiTranscript;
import com.knot.backend.recording.application.dto.result.AiWindowEvidence;
import com.knot.backend.recording.domain.RecordingErrorCode;
import com.knot.backend.recording.domain.RecordingException;
import com.knot.backend.recording.domain.model.RecordingTranscriptionWindow;
import com.knot.backend.recording.domain.model.TranscriptionWindowStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.ObjectMapper;

@Component
public class IncrementalTranscriptMerger {
    private static final long MAX_RECORDING_FRAMES = 115_200_000;
    private static final double FRAMES_PER_SECOND = 16_000;

    private final ObjectMapper mapper;

    public IncrementalTranscriptMerger(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AiTranscript merge(
            List<RecordingTranscriptionWindow> windows,
            long totalFrames,
            double similarityThreshold
    ) {
        return merge(
                windows,
                totalFrames,
                similarityThreshold,
                false
        );
    }

    /**
     * seamAligned이면 인접 구간의 겹친 단어를 정렬해 이어 붙이고, 아니면 단어 중앙 시각이 속한 core가 그 단어를 가진다.
     */
    public AiTranscript merge(
            List<RecordingTranscriptionWindow> windows,
            long totalFrames,
            double similarityThreshold,
            boolean seamAligned
    ) {
        if (windows == null || windows.isEmpty() || totalFrames <= 0 || totalFrames > MAX_RECORDING_FRAMES
                || !Double.isFinite(similarityThreshold) || similarityThreshold < -1 || similarityThreshold > 1
                || windows.stream()
                        .anyMatch(Objects::isNull)) {
            throw invalidData();
        }
        List<RecordingTranscriptionWindow> ordered = windows.stream()
                .sorted(Comparator.comparingLong(RecordingTranscriptionWindow::getCoreStartFrame))
                .toList();
        List<AiWindowEvidence> evidences = verifiedEvidence(
                ordered,
                totalFrames
        );
        List<List<OwnedToken>> owned = ownedTokens(
                ordered,
                evidences,
                totalFrames,
                seamAligned
        );
        List<AiTranscript.Utterance> utterances = new ArrayList<>();
        List<VoiceCentroid> centroids = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            Map<String, String> labels = assignSpeakers(
                    owned.get(index),
                    evidences.get(index),
                    centroids,
                    ordered.get(index)
                            .getWindowIndex(),
                    similarityThreshold
            );
            appendUtterances(
                    utterances,
                    owned.get(index),
                    labels
            );
        }
        return new AiTranscript(
                1,
                rawTranscript(utterances),
                utterances
        );
    }

    private List<AiWindowEvidence> verifiedEvidence(
            List<RecordingTranscriptionWindow> ordered,
            long totalFrames
    ) {
        String pipeline = ordered.getFirst()
                .getPipelineVersion();
        long recordingId = ordered.getFirst()
                .getRecordingId();
        String model = null;
        long expectedStart = 0;
        int expectedIndex = 0;
        List<AiWindowEvidence> evidences = new ArrayList<>();
        for (RecordingTranscriptionWindow window : ordered) {
            if (window.getRecordingId() != recordingId || !pipeline.equals(window.getPipelineVersion())
                    || window.getWindowIndex() != expectedIndex || window.getCoreStartFrame() != expectedStart
                    || window.getCoreEndFrame() > totalFrames || window.getInputEndFrame() > totalFrames
                    || window.getStatus() != TranscriptionWindowStatus.SUCCEEDED) {
                throw invalidData();
            }
            AiWindowEvidence evidence = readEvidence(window);
            if (model != null && !model.equals(evidence.modelVersion())) {
                throw invalidData();
            }
            model = evidence.modelVersion();
            evidences.add(evidence);
            expectedStart = window.getCoreEndFrame();
            expectedIndex++;
        }
        if (expectedStart != totalFrames) {
            throw invalidData();
        }
        return evidences;
    }

    private List<List<OwnedToken>> ownedTokens(
            List<RecordingTranscriptionWindow> ordered,
            List<AiWindowEvidence> evidences,
            long totalFrames,
            boolean seamAligned
    ) {
        if (seamAligned) {
            return alignedTokens(
                    ordered,
                    evidences
            );
        }
        List<List<OwnedToken>> owned = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            owned.add(
                    ownedTokens(
                            ordered.get(index),
                            evidences.get(index),
                            totalFrames
                    )
            );
        }
        return owned;
    }

    private List<List<OwnedToken>> alignedTokens(
            List<RecordingTranscriptionWindow> ordered,
            List<AiWindowEvidence> evidences
    ) {
        List<TranscriptSeamAligner.Window> windows = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            RecordingTranscriptionWindow window = ordered.get(index);
            long offset = evidences.get(index)
                    .inputStartFrame();
            windows.add(
                    new TranscriptSeamAligner.Window(
                            window.getCoreStartFrame(),
                            window.getCoreEndFrame(),
                            window.getInputStartFrame(),
                            window.getInputEndFrame(),
                            evidences.get(index)
                                    .tokens()
                                    .stream()
                                    .map(
                                            token -> new TranscriptSeamAligner.Token(
                                                    offset + token.startFrame(),
                                                    offset + token.endFrame(),
                                                    token.text()
                                                            .strip(),
                                                    token.speakerId()
                                            )
                                    )
                                    .toList()
                    )
            );
        }
        return TranscriptSeamAligner.stitch(windows)
                .stream()
                .map(
                        tokens -> tokens.stream()
                                .map(
                                        token -> new OwnedToken(
                                                token.startFrame(),
                                                token.endFrame(),
                                                token.text(),
                                                token.speakerId()
                                        )
                                )
                                .toList()
                )
                .toList();
    }

    private AiWindowEvidence readEvidence(RecordingTranscriptionWindow window) {
        if (!Integer.valueOf(1)
                .equals(window.getResultSchemaVersion()) || window.getResultJson() == null
                || !sha256(window.getResultJson()).equals(window.getResultSha256())
                || !sha256(window.getManifestJson()).equals(window.getManifestSha256())) {
            throw invalidData();
        }
        try (JsonParser parser = mapper.createParser(window.getResultJson())) {
            AiWindowEvidence evidence = mapper.readValue(
                    parser,
                    AiWindowEvidence.class
            );
            if (parser.nextToken() != null || evidence == null || !evidence.pipelineVersion()
                    .equals(window.getPipelineVersion()) || !evidence.inputManifestSha256()
                            .equals(window.getManifestSha256())
                    || evidence.inputStartFrame() != window.getInputStartFrame()
                    || evidence.inputEndFrame() != window.getInputEndFrame()) {
                throw invalidData();
            }
            return evidence;
        } catch (JacksonException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private List<OwnedToken> ownedTokens(
            RecordingTranscriptionWindow window,
            AiWindowEvidence evidence,
            long totalFrames
    ) {
        List<OwnedToken> owned = new ArrayList<>();
        for (AiWindowEvidence.Token token : evidence.tokens()) {
            long absoluteStart = evidence.inputStartFrame() + token.startFrame();
            long absoluteEnd = evidence.inputStartFrame() + token.endFrame();
            long doubledCenter = absoluteStart + absoluteEnd;
            boolean finalBoundary = absoluteStart == totalFrames && absoluteEnd == totalFrames
                    && window.getCoreEndFrame() == totalFrames;
            if (doubledCenter >= window.getCoreStartFrame() * 2
                    && (doubledCenter < window.getCoreEndFrame() * 2 || finalBoundary)) {
                owned.add(
                        new OwnedToken(
                                Math.max(
                                        absoluteStart,
                                        window.getCoreStartFrame()
                                ),
                                Math.min(
                                        absoluteEnd,
                                        window.getCoreEndFrame()
                                ),
                                token.text()
                                        .strip(),
                                token.speakerId()
                        )
                );
            }
        }
        return owned;
    }

    private Map<String, String> assignSpeakers(
            List<OwnedToken> tokens,
            AiWindowEvidence evidence,
            List<VoiceCentroid> centroids,
            int windowIndex,
            double threshold
    ) {
        Map<String, AiWindowEvidence.Speaker> available = new HashMap<>();
        evidence.speakers()
                .forEach(
                        speaker -> available.put(
                                speaker.speakerId(),
                                speaker
                        )
                );
        Map<String, AiWindowEvidence.Speaker> locals = new LinkedHashMap<>();
        tokens.forEach(
                token -> locals.putIfAbsent(
                        token.speakerId(),
                        available.get(token.speakerId())
                )
        );
        List<Candidate> candidates = new ArrayList<>();
        for (AiWindowEvidence.Speaker speaker : locals.values()) {
            if (speaker.embedding()
                    .isEmpty()) {
                continue;
            }
            double[] vector = normalized(speaker.embedding());
            for (int index = 0; index < centroids.size(); index++) {
                double score = centroids.get(index)
                        .similarity(vector);
                if (score >= threshold) {
                    candidates.add(
                            new Candidate(
                                    speaker.speakerId(),
                                    index,
                                    score
                            )
                    );
                }
            }
        }
        candidates.sort(
                Comparator.comparingDouble(Candidate::score)
                        .reversed()
                        .thenComparing(Candidate::speakerId)
                        .thenComparingInt(Candidate::label)
        );
        Map<String, Integer> labels = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!labels.containsKey(candidate.speakerId()) && used.add(candidate.label())) {
                labels.put(
                        candidate.speakerId(),
                        candidate.label()
                );
            }
        }
        Map<String, String> speakerLabels = new HashMap<>();
        for (AiWindowEvidence.Speaker speaker : locals.values()) {
            if (speaker.embedding()
                    .isEmpty()) {
                speakerLabels.put(
                        speaker.speakerId(),
                        "UNKNOWN_" + windowIndex + "_" + speaker.speakerId()
                );
                continue;
            }
            Integer label = labels.get(speaker.speakerId());
            if (label == null) {
                label = centroids.size();
                labels.put(
                        speaker.speakerId(),
                        label
                );
                centroids.add(new VoiceCentroid(speaker));
            } else {
                centroids.get(label)
                        .include(speaker);
            }
            speakerLabels.put(
                    speaker.speakerId(),
                    "SPEAKER_" + label
            );
        }
        return speakerLabels;
    }

    private void appendUtterances(
            List<AiTranscript.Utterance> utterances,
            List<OwnedToken> tokens,
            Map<String, String> labels
    ) {
        for (OwnedToken token : tokens) {
            String speakerLabel = labels.get(token.speakerId());
            double start = token.startFrame() / FRAMES_PER_SECOND;
            double end = token.endFrame() / FRAMES_PER_SECOND;
            AiTranscript.Utterance previous = utterances.isEmpty() ? null : utterances.getLast();
            if (previous != null && previous.speakerLabel()
                    .equals(speakerLabel)) {
                utterances.set(
                        utterances.size() - 1,
                        new AiTranscript.Utterance(
                                previous.ord(),
                                speakerLabel,
                                previous.startSec(),
                                Math.max(
                                        previous.endSec(),
                                        end
                                ),
                                previous.text() + " " + token.text()
                        )
                );
            } else {
                utterances.add(
                        new AiTranscript.Utterance(
                                utterances.size(),
                                speakerLabel,
                                start,
                                end,
                                token.text()
                        )
                );
            }
        }
    }

    private String rawTranscript(List<AiTranscript.Utterance> utterances) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (AiTranscript.Utterance utterance : utterances) {
            Map<String, Object> offsets = new LinkedHashMap<>();
            offsets.put(
                    "from",
                    Math.round(utterance.startSec() * 1000)
            );
            offsets.put(
                    "to",
                    Math.round(utterance.endSec() * 1000)
            );
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put(
                    "text",
                    utterance.text()
            );
            segment.put(
                    "offsets",
                    offsets
            );
            segments.add(segment);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(
                "transcription",
                segments
        );
        return mapper.writeValueAsString(root);
    }

    private static double[] normalized(List<Double> embedding) {
        double norm = 0;
        for (double component : embedding) {
            norm = Math.hypot(
                    norm,
                    component
            );
        }
        double[] vector = new double[embedding.size()];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = embedding.get(index) / norm;
        }
        return vector;
    }

    private static String sha256(String content) {
        if (content == null) {
            throw invalidData();
        }
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8))
                    );
        } catch (NoSuchAlgorithmException exception) {
            throw new RecordingException(
                    RecordingErrorCode.INVALID_RECORDING_DATA,
                    exception
            );
        }
    }

    private static RecordingException invalidData() {
        return new RecordingException(RecordingErrorCode.INVALID_RECORDING_DATA);
    }

    private record OwnedToken(
            long startFrame,
            long endFrame,
            String text,
            String speakerId
    ) {
    }

    private record Candidate(
            String speakerId,
            int label,
            double score
    ) {
    }

    private static final class VoiceCentroid {
        private final double[] weightedVector;
        private long weight;

        private VoiceCentroid(AiWindowEvidence.Speaker speaker) {
            weightedVector = normalized(speaker.embedding());
            weight = speaker.voicedFrames();
        }

        private double similarity(double[] vector) {
            if (weightedVector.length != vector.length) {
                return -2;
            }
            double norm = 0;
            double product = 0;
            for (int index = 0; index < vector.length; index++) {
                norm = Math.hypot(
                        norm,
                        weightedVector[index]
                );
                product += weightedVector[index] * vector[index];
            }
            return norm == 0 ? -2 : product / norm;
        }

        private void include(AiWindowEvidence.Speaker speaker) {
            double[] vector = normalized(speaker.embedding());
            long nextWeight = weight + speaker.voicedFrames();
            for (int index = 0; index < vector.length; index++) {
                weightedVector[index] = (weightedVector[index] * weight + vector[index] * speaker.voicedFrames())
                        / nextWeight;
            }
            weight = nextWeight;
        }
    }
}
