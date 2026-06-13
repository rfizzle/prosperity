package com.rfizzle.prosperity.api;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks a type/member as part of Prosperity's stable public API (Concord API-STANDARD v1).
 */
@Documented
@Retention(RetentionPolicy.CLASS)
public @interface Stable {
}
