package com.yu030x.booking.common.exception;

import com.yu030x.booking.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    ResponseEntity<Result<Void>> biz(BizException exception) {
        return ResponseEntity.status(exception.errorCode.httpStatus)
                .body(new Result<>(exception.errorCode.code, exception.getMessage(), null));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<Result<Void>> invalidParameter(Exception exception) {
        ErrorCode code = ErrorCode.INVALID_PARAMETER;
        return ResponseEntity.status(code.httpStatus).body(new Result<>(code.code, "invalid parameter", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> other(Exception exception) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus).body(new Result<>(code.code, "internal server error", null));
    }
}
