package com.knot.backend.recording.infrastructure.llm;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MeetingSummarySecretFilter {
    private static final Pattern LABELED_CODE = Pattern
            .compile("(?i)(?:인증\\s*(?:코드|번호)|확인\\s*(?:코드|번호)|일회용\\s*비밀번호|OTP)\\D{0,30}(?<![0-9])([0-9]{4,8})(?![0-9])");

    private MeetingSummarySecretFilter() {}

    static String filter(
            String original,
            String summary
    ) {
        Set<String> codes = new LinkedHashSet<>();
        collect(
                original,
                codes
        );
        collect(
                summary,
                codes
        );
        String result = summary;
        for (String code : codes) {
            result = result.replaceAll(
                    "(?<![0-9])" + Pattern.quote(code) + "(?![0-9])",
                    "[인증 코드 제외]"
            );
        }
        return result;
    }

    private static void collect(
            String value,
            Set<String> codes
    ) {
        if (value == null) {
            return;
        }
        Matcher matcher = LABELED_CODE.matcher(value);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
    }
}
