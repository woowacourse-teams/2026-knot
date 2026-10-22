package com.knot.backend.document.application;

import com.knot.backend.document.domain.model.DocumentCategory;
import com.knot.backend.document.domain.model.DocumentCategoryAssignmentSource;
import com.knot.backend.document.domain.model.DocumentCategoryDecision;
import com.knot.backend.document.domain.model.DocumentText;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentCategoryFallbackClassifier {
    public static final String RULE_VERSION = "2026-10-22-v1";
    private static final List<CategoryRule> RULES = List.of(
            new CategoryRule(
                    "녹음·전사",
                    "음성 녹음, 업로드, 전사와 STT 품질",
                    List.of(
                            "녹음",
                            "전사",
                            "음성",
                            "stt",
                            "오디오"
                    )
            ),
            new CategoryRule(
                    "배포·운영",
                    "서버 배포, 인프라와 서비스 운영",
                    List.of(
                            "배포",
                            "인프라",
                            "서버 운영",
                            "무중단",
                            "모니터링"
                    )
            ),
            new CategoryRule(
                    "문서·검색",
                    "문서 생성, 보관과 검색",
                    List.of(
                            "문서",
                            "검색",
                            "폴더",
                            "보관"
                    )
            ),
            new CategoryRule(
                    "알림",
                    "서비스 알림의 전달 방식과 문구",
                    List.of(
                            "알림",
                            "알람"
                    )
            ),
            new CategoryRule(
                    "사용자·권한",
                    "계정, 인증, 멤버와 접근 권한",
                    List.of(
                            "회원",
                            "인증",
                            "권한",
                            "로그인",
                            "탈퇴",
                            "초대"
                    )
            )
    );

    public DocumentCategoryDecision classify(
            String agendaTopic,
            String title,
            String content,
            List<DocumentCategory> categories
    ) {
        List<CategoryRule> matches = match(agendaTopic);
        if (matches.isEmpty()) {
            matches = match(title);
        }
        if (matches.size() != 1) {
            return DocumentCategoryDecision.fallback();
        }
        return classifyRule(
                matches.getFirst(),
                categories
        );
    }

    private DocumentCategoryDecision classifyRule(
            CategoryRule rule,
            List<DocumentCategory> categories
    ) {
        List<DocumentCategory> matches = categories.stream()
                .filter(category -> !category.isFallback())
                .filter(category -> rule.matches(category.getName() + " " + category.getDescription()))
                .toList();
        if (matches.size() > 1) {
            return DocumentCategoryDecision.fallback();
        }
        if (matches.size() == 1) {
            DocumentCategory category = matches.getFirst();
            return DocumentCategoryDecision.reuse(
                    category.getId(),
                    DocumentCategoryAssignmentSource.RULE_REUSE
            );
        }
        return DocumentCategoryDecision.create(
                rule.name(),
                rule.description(),
                DocumentCategoryAssignmentSource.RULE_NEW
        );
    }

    private List<CategoryRule> match(String value) {
        if (DocumentText.isBlank(value)) {
            return List.of();
        }
        return RULES.stream()
                .filter(rule -> rule.matches(value))
                .toList();
    }
}
