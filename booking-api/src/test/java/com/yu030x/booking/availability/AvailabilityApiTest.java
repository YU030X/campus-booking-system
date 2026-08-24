package com.yu030x.booking.availability;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yu030x.booking.common.config.JacksonConfig;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.resource.ResourceTestSecurityConfig;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AvailabilityController.class)
@Import({ResourceTestSecurityConfig.class, GlobalExceptionHandler.class, JacksonConfig.class})
class AvailabilityApiTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private AvailabilityService service;

    @Test
    void anonymousIsCanonical401() throws Exception {
        mvc.perform(get("/api/v1/resources/7/available-slots").param("date", "2026-08-17"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void authenticatedPayloadHasFrozenShape() throws Exception {
        when(service.get(7, LocalDate.of(2026, 8, 17))).thenReturn(new AvailabilityVO(
                "7",
                LocalDate.of(2026, 8, 17),
                30,
                List.of(
                        new AvailabilityVO.SlotVO("09:00", "09:30", true),
                        new AvailabilityVO.SlotVO("09:30", "10:00", false))));

        mvc.perform(get("/api/v1/resources/7/available-slots")
                        .param("date", "2026-08-17")
                        .header("X-Test-Role", "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resourceId").value("7"))
                .andExpect(jsonPath("$.data.date").value("2026-08-17"))
                .andExpect(jsonPath("$.data.slotMinutes").value(30))
                .andExpect(jsonPath("$.data.slots[0].startTime").value("09:00"))
                .andExpect(jsonPath("$.data.slots[1].available").value(false));
    }

    @Test
    void missingOrMalformedDateIs400() throws Exception {
        mvc.perform(get("/api/v1/resources/7/available-slots").header("X-Test-Role", "STUDENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
        mvc.perform(get("/api/v1/resources/7/available-slots")
                        .param("date", "2026-02-30")
                        .header("X-Test-Role", "STUDENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void missingAndUnavailableResourcesUseCanonicalErrors() throws Exception {
        when(service.get(8, LocalDate.of(2026, 8, 17)))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "resource not found"));
        when(service.get(9, LocalDate.of(2026, 8, 17)))
                .thenThrow(new BizException(ErrorCode.RESOURCE_ERROR, "resource unavailable"));

        mvc.perform(get("/api/v1/resources/8/available-slots")
                        .param("date", "2026-08-17")
                        .header("X-Test-Role", "STUDENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/api/v1/resources/9/available-slots")
                        .param("date", "2026-08-17")
                        .header("X-Test-Role", "STUDENT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(42000));
    }
}
