package com.example.jobqueue.job;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SupportedTaskTypeValidator.class)
public @interface SupportedTaskType {

    String message() default "must be one of the supported task types";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
