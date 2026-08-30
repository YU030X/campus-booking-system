package com.yu030x.booking.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose invocation outcome must be persisted into
 * {@code operation_log}. Only action values registered in the approved
 * action-key registry ({@code OperationAction}) produce writes; unknown or
 * unapproved values are ignored without side effects.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OperationLog {

    /** Approved action key; must match {@code OperationAction} constant name. */
    String value();
}
