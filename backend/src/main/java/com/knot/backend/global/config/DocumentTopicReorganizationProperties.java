package com.knot.backend.global.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties("knot.document-topic-reorganization")
public class DocumentTopicReorganizationProperties {

    private boolean enabled = false;
    @Min(1)
    @Max(25)
    private int batchSize = 25;
    @Min(4096)
    @Max(120000)
    private int maxInputCharacters = 60000;
}
