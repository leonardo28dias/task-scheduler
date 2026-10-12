package com.example.jobqueue.job;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class SupportedTaskTypeValidator implements ConstraintValidator<SupportedTaskType, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || SupportedTaskTypes.ALL.contains(value);
    }
}
