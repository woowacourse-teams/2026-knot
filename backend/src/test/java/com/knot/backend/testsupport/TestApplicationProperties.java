package com.knot.backend.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = {"auth.jwt.secret=test-jwt-secret-012345678901234567890123456789",
        "auth.jwt.secure=false", "auth.jwt.cookie-name=KNOT_ACCESS_TOKEN",
        "auth.cors.allowed-origins=https://knoted.kr,http://localhost:3000",
        "workspace.invitation.lookup-hash-key=ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA",
        "spring.datasource.username=knot", "spring.datasource.password=knot",
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "recording.audio-storage.region=ap-northeast-2", "recording.audio-storage.bucket=knot-test-audio",
        "recording.connection-expiry.enabled=false"})
public @interface TestApplicationProperties {
}
