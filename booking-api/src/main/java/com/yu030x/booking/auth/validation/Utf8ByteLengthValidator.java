package com.yu030x.booking.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, String> {
    private int min;
    private int max;

    @Override
    public void initialize(Utf8ByteLength annotation) {
        min = annotation.min();
        max = annotation.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        return length >= min && length <= max;
    }
}
