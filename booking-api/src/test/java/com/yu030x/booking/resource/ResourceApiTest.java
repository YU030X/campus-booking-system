package com.yu030x.booking.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yu030x.booking.common.config.JacksonConfig;
import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.resource.controller.CategoryController;
import com.yu030x.booking.resource.controller.ResourceController;
import com.yu030x.booking.resource.service.CategoryService;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import com.yu030x.booking.resource.vo.CategoryVO;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@WebMvcTest({CategoryController.class, ResourceController.class})
@Import({ResourceTestSecurityConfig.class, GlobalExceptionHandler.class, JacksonConfig.class})
class ResourceApiTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;
    @MockBean
    private CategoryService categoryService;
    @MockBean
    private ResourceCatalogService resourceService;

    @Test
    void authenticatedReadUsesCanonicalEnvelopeAndStringLong() throws Exception {
        when(categoryService.tree()).thenReturn(List.of(new CategoryVO(
                "9007199254740993",
                "Rooms",
                "0",
                0,
                null,
                LocalDateTime.of(2026, 8, 13, 10, 0),
                LocalDateTime.of(2026, 8, 13, 10, 0),
                List.of())));

        mockMvc.perform(get("/api/v1/categories").header("X-Test-Role", "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data[0].parentId").value("0"))
                .andExpect(jsonPath("$.data[0].deleted").doesNotExist());
    }

    @Test
    void readsRequirePrincipalAndWritesRequireAdminAuthority() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(content().json("{\"code\":40100,\"message\":\"unauthenticated\",\"data\":null}"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("X-Test-Role", "STUDENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rooms\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void validationFailureHasCanonicalNullData() throws Exception {
        when(categoryService.create(any())).thenThrow(
                new BizException(ErrorCode.INVALID_PARAMETER, "invalid parameter"));

        mockMvc.perform(post("/api/v1/admin/categories")
                        .header("X-Test-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000))
                .andExpect(content().json("{\"code\":40000,\"message\":\"invalid parameter\",\"data\":null}"));
    }

    @Test
    void controllersExposeOnlyFrozenResourceCatalogRoutes() {
        Set<String> actual = new HashSet<>();
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            if (method.getBeanType().getPackageName().startsWith("com.yu030x.booking.resource.controller")) {
                for (String pattern : mapping.getPatternValues()) {
                    for (RequestMethod requestMethod : mapping.getMethodsCondition().getMethods()) {
                        actual.add(requestMethod.name() + " " + pattern);
                    }
                }
            }
        });

        assertEquals(Set.of(
                "GET /api/v1/categories",
                "POST /api/v1/admin/categories",
                "PUT /api/v1/admin/categories/{id}",
                "DELETE /api/v1/admin/categories/{id}",
                "GET /api/v1/resources",
                "GET /api/v1/resources/{id}",
                "POST /api/v1/admin/resources",
                "PUT /api/v1/admin/resources/{id}",
                "PATCH /api/v1/admin/resources/{id}/status",
                "PUT /api/v1/admin/resources/{id}/time-rules",
                "POST /api/v1/admin/resources/{id}/closures",
                "DELETE /api/v1/admin/resources/{id}/closures/{closureId}"), actual);
    }
}
