package com.rfizzle.prosperity.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as part of Prosperity's stable public API surface (Concord API
 * Standard v1): its signature survives minor and patch releases, and a breaking
 * change requires a major version bump plus a changelog entry naming the broken
 * signature.
 *
 * <p>This is the suite's stability marker: a local annotation rather than
 * {@code @ApiStatus.Stable} (which has no member in
 * {@code org.jetbrains.annotations.ApiStatus}). Per the suite's no-shared-jar
 * rule, each mod declares its own {@code api.Stable} and applies it to every
 * {@code api} class; internal classes use the real {@code @ApiStatus.Internal}
 * instead.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Stable {
}
