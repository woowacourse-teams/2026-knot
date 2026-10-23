package com.knot.backend.document.application;

import com.knot.backend.document.domain.DocumentErrorCode;
import com.knot.backend.document.domain.DocumentException;
import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.document.domain.model.DocumentGenerationJob;
import com.knot.backend.document.domain.model.PreparedDocument;
import com.knot.backend.document.domain.repository.DocumentCategoryRepository;
import com.knot.backend.global.config.DocumentCategoryProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentCategoryCatalogService {

    private final DocumentCategoryRepository categories;
    private final DocumentCategoryFallbackClassifier fallback;
    private final DocumentCategoryProperties properties;

    public List<DocumentCategory> normalCategories(long workspaceId) {
        return categories.findAllByWorkspaceId(workspaceId)
                .stream()
                .filter(category -> !category.isFallback())
                .toList();
    }

    public String version(List<DocumentCategory> catalog) {
        StringBuilder canonical = new StringBuilder();
        for (DocumentCategory category : catalog) {
            canonical.append(category.getId())
                    .append(':')
                    .append(category.getName())
                    .append(':')
                    .append(category.getDescription())
                    .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    canonical.toString()
                            .getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of()
                    .formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new DocumentException(
                    DocumentErrorCode.INVALID_DOCUMENT_DATA,
                    exception
            );
        }
    }

    public DocumentCategoryDecision validateDecision(
            DocumentCategoryDecision decision,
            List<DocumentCategory> catalog
    ) {
        if (decision == null) {
            throw invalidResponse();
        }
        Long existingId = decision.getExistingCategoryId();
        if (existingId != null) {
            requireCandidate(
                    existingId,
                    catalog
            );
            return decision;
        }
        if (decision.isFallback()) {
            return decision;
        }
        Optional<DocumentCategory> exact = catalog.stream()
                .filter(
                        category -> category.getNormalizedName()
                                .equals(decision.getNewName())
                )
                .findFirst();
        if (exact.isPresent()) {
            DocumentCategory category = exact.orElseThrow(this::invalidResponse);
            return DocumentCategoryDecision.reuse(
                    category.getId(),
                    reuseSource(decision)
            );
        }
        return decision;
    }

    public DocumentCategoryDecision fallbackFor(
            DocumentGenerationJob job,
            PreparedDocument body,
            List<DocumentCategory> catalog
    ) {
        try {
            return fallback.classify(
                    job.getTopic(),
                    body.getTitle(),
                    body.getContent(),
                    catalog
            );
        } catch (RuntimeException exception) {
            job.recordCategoryFailure("RULE_CLASSIFICATION_ERROR");
            log.warn(
                    "Document category rule fallback workspaceId={} jobId={} ruleVersion={}",
                    body.getWorkspaceId(),
                    job.getId(),
                    DocumentCategoryFallbackClassifier.RULE_VERSION
            );
            return DocumentCategoryDecision.fallback();
        }
    }

    public boolean canCall(
            DocumentGenerationJob job,
            Instant now
    ) {
        return job.hasCategoryTimeAt(now) && job.getCategoryAttemptCount() <= properties.getMaxRetries();
    }

    public Optional<DocumentCategory> resolveForPublication(
            DocumentGenerationJob job,
            PreparedDocument body,
            Instant now
    ) {
        List<DocumentCategory> all = categories.findAllByWorkspaceId(body.getWorkspaceId());
        List<DocumentCategory> normal = all.stream()
                .filter(category -> !category.isFallback())
                .toList();
        DocumentCategoryDecision decision = validateDecision(
                body.getDecision(),
                normal
        );
        String currentVersion = version(normal);
        if (needsReview(
                decision,
                body.getCatalogVersion(),
                currentVersion
        )) {
            return resolveChangedCatalog(
                    job,
                    body,
                    normal,
                    all,
                    now,
                    currentVersion
            );
        }
        body.assign(
                decision,
                currentVersion,
                DocumentCategoryFallbackClassifier.RULE_VERSION
        );
        return Optional.of(
                resolve(
                        body.getWorkspaceId(),
                        decision,
                        all,
                        now
                )
        );
    }

    private Optional<DocumentCategory> resolveChangedCatalog(
            DocumentGenerationJob job,
            PreparedDocument body,
            List<DocumentCategory> normal,
            List<DocumentCategory> all,
            Instant now,
            String currentVersion
    ) {
        if (canCall(
                job,
                now
        )) {
            return Optional.empty();
        }
        DocumentCategoryDecision decision = fallbackFor(
                job,
                body,
                normal
        );
        body.assign(
                decision,
                currentVersion,
                DocumentCategoryFallbackClassifier.RULE_VERSION
        );
        job.recordCategoryFailure("CATALOG_CHANGED");
        return Optional.of(
                resolve(
                        body.getWorkspaceId(),
                        decision,
                        all,
                        now
                )
        );
    }

    public boolean needsReview(
            DocumentCategoryDecision decision,
            String expectedVersion,
            String currentVersion
    ) {
        return decision.isNew() && !currentVersion.equals(expectedVersion);
    }

    private DocumentCategory resolve(
            long workspaceId,
            DocumentCategoryDecision decision,
            List<DocumentCategory> catalog,
            Instant now
    ) {
        if (decision.isFallback()) {
            return catalog.stream()
                    .filter(DocumentCategory::isFallback)
                    .findFirst()
                    .orElseGet(
                            () -> categories.save(
                                    DocumentCategory.createFallback(
                                            workspaceId,
                                            now
                                    )
                            )
                    );
        }
        if (decision.getExistingCategoryId() != null) {
            return requireCandidate(
                    decision.getExistingCategoryId(),
                    catalog
            );
        }
        return categories.save(
                DocumentCategory.create(
                        workspaceId,
                        decision.getNewName(),
                        decision.getNewDescription(),
                        now
                )
        );
    }

    private DocumentCategory requireCandidate(
            long id,
            List<DocumentCategory> catalog
    ) {
        return catalog.stream()
                .filter(category -> !category.isFallback())
                .filter(category -> category.getId() == id)
                .findFirst()
                .orElseThrow(this::invalidResponse);
    }

    private DocumentCategoryAssignmentSource reuseSource(DocumentCategoryDecision decision) {
        if (decision.getSource() == DocumentCategoryAssignmentSource.AI_NEW) {
            return DocumentCategoryAssignmentSource.AI_REUSE;
        }
        return DocumentCategoryAssignmentSource.RULE_REUSE;
    }

    private DocumentException invalidResponse() {
        return new DocumentException(DocumentErrorCode.INVALID_DOCUMENT_CATEGORY_RESPONSE);
    }
}
