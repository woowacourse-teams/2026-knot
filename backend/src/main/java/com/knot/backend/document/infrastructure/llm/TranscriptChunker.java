package com.knot.backend.document.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;

final class TranscriptChunker {
    List<String> split(
            String input,
            int maxCharacters
    ) {
        if (maxCharacters < 2) {
            throw new IllegalArgumentException("구간 크기는 2 이상이어야 합니다");
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < input.length()) {
            int end = Math.min(
                    start + maxCharacters,
                    input.length()
            );
            if (end < input.length()) {
                int newline = input.lastIndexOf(
                        '\n',
                        end - 1
                );
                if (newline >= start) {
                    end = newline + 1;
                }
                if (Character.isHighSurrogate(input.charAt(end - 1)) && Character.isLowSurrogate(input.charAt(end))) {
                    end--;
                }
            }
            chunks.add(
                    input.substring(
                            start,
                            end
                    )
            );
            start = end;
        }
        return List.copyOf(chunks);
    }
}
