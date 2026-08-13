package com.yu030x.booking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class RequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void normalizesRegistrationWithoutTrimmingPassword() {
        RegisterRequest request = new RegisterRequest("  user_01  ", " pass word ", "  张三  ",
                "  ", " 13800138000 ", " test@example.com ", " /a.png ");
        assertThat(request.username()).isEqualTo("user_01");
        assertThat(request.password()).isEqualTo(" pass word ");
        assertThat(request.realName()).isEqualTo("张三");
        assertThat(request.studentNo()).isNull();
        assertThat(request.phone()).isEqualTo("13800138000");
        assertThat(request.email()).isEqualTo("test@example.com");
        assertThat(request.avatar()).isEqualTo("/a.png");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void validatesPasswordByUtf8Bytes() {
        RegisterRequest seventyTwoBytes = valid("你".repeat(24));
        RegisterRequest seventyFiveBytes = valid("你".repeat(25));
        assertThat(validator.validate(seventyTwoBytes)).isEmpty();
        assertThat(validator.validate(seventyFiveBytes))
                .anyMatch(violation -> "password".equals(violation.getPropertyPath().toString()));
    }

    @Test
    void allAuthDtosRejectUnknownFieldsEvenWhenMapperGloballyIgnoresThem() {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"username\":\"student01\",\"password\":\"password8\",\"realName\":\"A\",\"role\":\"ADMIN\"}",
                RegisterRequest.class)).hasMessageContaining("unknown field: role");
        assertThatThrownBy(() -> mapper.readValue(
                "{\"username\":\"student01\",\"password\":\"password8\",\"extra\":1}",
                LoginRequest.class)).hasMessageContaining("unknown field: extra");
    }

    private RegisterRequest valid(String password) {
        return new RegisterRequest("student01", password, "Student", null, null, null, null);
    }
}
