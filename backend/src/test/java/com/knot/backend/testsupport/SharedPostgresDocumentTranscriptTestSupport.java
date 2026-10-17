package com.knot.backend.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class SharedPostgresDocumentTranscriptTestSupport {

    @DynamicPropertySource
    public static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgresTestSupport.registerDataSourceProperties(
                registry,
                "document_transcript"
        );
    }
}
