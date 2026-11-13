package com.knot.backend.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.ContextConfiguration;

/** Shares the PostgreSQL process, not the database, between Spring contexts. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ContextConfiguration(initializers = IsolatedPostgresInitializer.class)
public @interface IsolatedPostgresTest {
}
