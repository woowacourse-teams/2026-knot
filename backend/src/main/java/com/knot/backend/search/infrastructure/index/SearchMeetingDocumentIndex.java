package com.knot.backend.search.infrastructure.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;

public final class SearchMeetingDocumentIndex {
    private static final int CHUNK_SIZE = 240;
    private static final int CHUNK_OVERLAP = 32;
    private static final int CANDIDATE_CHUNKS = 60;
    private static final int RRF_K = 60;
    private static final String INDEX_VERSION = "1";
    private static final String DOCUMENT_KEY = "documentKey";
    private static final String DOCUMENT_ID = "documentId";
    private static final String FINGERPRINT = "fingerprint";
    private static final String TEXT = "text";
    private static final String VECTOR = "vector";

    private final Path root;
    private final SearchE5EmbeddingClient embeddings;
    private final String modelRevision;

    public SearchMeetingDocumentIndex(
            Path root,
            SearchE5EmbeddingClient embeddings,
            String modelRevision
    ) {
        this.root = root;
        this.embeddings = embeddings;
        this.modelRevision = modelRevision;
    }

    public boolean reconcile(
            long workspaceId,
            Supplier<Map<Long, String>> source
    ) throws IOException {
        Path workspace = Files.createDirectories(
                workspacePath(workspaceId),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        );
        try (KoreanAnalyzer analyzer = new KoreanAnalyzer();
                Directory directory = FSDirectory.open(workspace);
                IndexWriter writer = new IndexWriter(
                        directory,
                        new IndexWriterConfig(analyzer).setCommitOnClose(false)
                );
                DirectoryReader reader = DirectoryReader.open(writer)) {
            Map<Long, String> documents = new TreeMap<>(source.get());
            Map<Long, String> indexed = fingerprints(new IndexSearcher(reader));
            for (Map.Entry<Long, String> document : documents.entrySet()) {
                long documentId = document.getKey();
                String content = document.getValue();
                String fingerprint = fingerprint(content);
                if (!fingerprint.equals(indexed.remove(documentId))) {
                    replaceDocument(
                            writer,
                            documentId,
                            content,
                            fingerprint
                    );
                }
            }
            for (long removed : indexed.keySet()) {
                writer.deleteDocuments(documentKey(removed));
            }
            if (!documents.equals(source.get())) {
                return false;
            }
            writer.commit();
            return true;
        }
    }

    public List<Long> find(
            long workspaceId,
            String question,
            Set<Long> allowedDocumentIds,
            int limit
    ) throws IOException {
        return find(
                workspaceId,
                question,
                allowedDocumentIds,
                Map.of(),
                limit
        ).orElse(List.of());
    }

    Optional<List<Long>> findCurrent(
            long workspaceId,
            String question,
            Map<Long, String> documents,
            int limit
    ) throws IOException {
        return find(
                workspaceId,
                question,
                documents.keySet(),
                documents,
                limit
        );
    }

    private Optional<List<Long>> find(
            long workspaceId,
            String question,
            Set<Long> allowedDocumentIds,
            Map<Long, String> documents,
            int limit
    ) throws IOException {
        Path workspace = workspacePath(workspaceId);
        if (allowedDocumentIds.isEmpty() || limit < 1) {
            return Optional.of(List.of());
        }
        if (!Files.isDirectory(workspace)) {
            return Optional.empty();
        }
        try (KoreanAnalyzer analyzer = new KoreanAnalyzer(); Directory directory = FSDirectory.open(workspace)) {
            if (!DirectoryReader.indexExists(directory)) {
                return Optional.empty();
            }
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                if (!containsCurrentDocuments(
                        searcher,
                        documents
                )) {
                    return Optional.empty();
                }
                searcher.setSimilarity(new BM25Similarity());
                Query filter = LongPoint.newSetQuery(
                        DOCUMENT_ID,
                        allowedDocumentIds
                );
                List<Long> lexical = lexicalMatches(
                        searcher,
                        analyzer,
                        question,
                        filter
                );
                Query vector = new KnnFloatVectorQuery(
                        VECTOR,
                        embeddings.embedQuery(question),
                        CANDIDATE_CHUNKS,
                        filter
                );
                List<Long> semantic = documentIds(
                        searcher,
                        vector
                );
                return Optional.of(
                        fuse(
                                lexical,
                                semantic,
                                limit
                        )
                );
            }
        }
    }

    private boolean containsCurrentDocuments(
            IndexSearcher searcher,
            Map<Long, String> documents
    ) throws IOException {
        if (documents.isEmpty()) {
            return true;
        }
        Map<Long, String> indexed = fingerprints(searcher);
        return documents.entrySet()
                .stream()
                .allMatch(document -> fingerprint(document.getValue()).equals(indexed.get(document.getKey())));
    }

    private Path workspacePath(long workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("Workspace ID must be positive");
        }
        return root.resolve(Long.toString(workspaceId));
    }

    private Map<Long, String> fingerprints(IndexSearcher searcher) throws IOException {
        Map<Long, String> fingerprints = new HashMap<>();
        for (ScoreDoc hit : searcher.search(
                new MatchAllDocsQuery(),
                Math.max(
                        1,
                        searcher.getIndexReader()
                                .numDocs()
                )
        ).scoreDocs) {
            Document fields = searcher.storedFields()
                    .document(hit.doc);
            fingerprints.put(
                    fields.getField(DOCUMENT_ID)
                            .numericValue()
                            .longValue(),
                    fields.get(FINGERPRINT)
            );
        }
        return fingerprints;
    }

    private void replaceDocument(
            IndexWriter writer,
            long id,
            String text,
            String fingerprint
    ) throws IOException {
        List<String> chunks = chunks(text);
        List<float[]> vectors = embeddings.embedPassages(chunks);
        List<Document> entries = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            entries.add(
                    entry(
                            id,
                            fingerprint,
                            chunks.get(index),
                            vectors.get(index)
                    )
            );
        }
        writer.updateDocuments(
                documentKey(id),
                entries
        );
    }

    private Document entry(
            long id,
            String fingerprint,
            String text,
            float[] vector
    ) {
        Document document = new Document();
        document.add(
                new StringField(
                        DOCUMENT_KEY,
                        Long.toString(id),
                        Field.Store.NO
                )
        );
        document.add(
                new LongPoint(
                        DOCUMENT_ID,
                        id
                )
        );
        document.add(
                new StoredField(
                        DOCUMENT_ID,
                        id
                )
        );
        document.add(
                new StoredField(
                        FINGERPRINT,
                        fingerprint
                )
        );
        document.add(
                new TextField(
                        TEXT,
                        text,
                        Field.Store.NO
                )
        );
        document.add(
                new KnnFloatVectorField(
                        VECTOR,
                        vector,
                        VectorSimilarityFunction.COSINE
                )
        );
        return document;
    }

    private List<String> chunks(String text) {
        List<String> sections = new ArrayList<>();
        int remaining = text.codePointCount(
                0,
                text.length()
        );
        int start = 0;
        while (remaining > CHUNK_SIZE) {
            int end = text.offsetByCodePoints(
                    start,
                    CHUNK_SIZE
            );
            sections.add(
                    text.substring(
                            start,
                            end
                    )
            );
            start = text.offsetByCodePoints(
                    end,
                    -CHUNK_OVERLAP
            );
            remaining -= CHUNK_SIZE - CHUNK_OVERLAP;
        }
        if (remaining > 0) {
            sections.add(text.substring(start));
        }
        return sections;
    }

    private String fingerprint(String text) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((INDEX_VERSION + "\n" + modelRevision + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of()
                    .formatHex(sha256.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    private Term documentKey(long documentId) {
        return new Term(
                DOCUMENT_KEY,
                Long.toString(documentId)
        );
    }

    private List<Long> lexicalMatches(
            IndexSearcher searcher,
            KoreanAnalyzer analyzer,
            String question,
            Query filter
    ) throws IOException {
        Query query = new QueryBuilder(analyzer).createBooleanQuery(
                TEXT,
                question
        );
        if (query == null) {
            return List.of();
        }
        BooleanQuery scoped = new BooleanQuery.Builder().add(
                query,
                BooleanClause.Occur.MUST
        )
                .add(
                        filter,
                        BooleanClause.Occur.FILTER
                )
                .build();
        return documentIds(
                searcher,
                scoped
        );
    }

    private List<Long> documentIds(
            IndexSearcher searcher,
            Query query
    ) throws IOException {
        Set<Long> ids = new LinkedHashSet<>();
        for (ScoreDoc hit : searcher.search(
                query,
                CANDIDATE_CHUNKS
        ).scoreDocs) {
            Document fields = searcher.storedFields()
                    .document(hit.doc);
            ids.add(
                    fields.getField(DOCUMENT_ID)
                            .numericValue()
                            .longValue()
            );
        }
        return List.copyOf(ids);
    }

    private List<Long> fuse(
            List<Long> lexical,
            List<Long> semantic,
            int limit
    ) {
        Map<Long, Double> scores = new HashMap<>();
        addRanks(
                scores,
                lexical
        );
        addRanks(
                scores,
                semantic
        );
        return scores.entrySet()
                .stream()
                .sorted(
                        Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey())
                )
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void addRanks(
            Map<Long, Double> scores,
            List<Long> ranked
    ) {
        for (int index = 0; index < ranked.size(); index++) {
            double contribution = 1.0 / (RRF_K + index + 1);
            scores.merge(
                    ranked.get(index),
                    contribution,
                    Double::sum
            );
        }
    }
}
