package com.yu030x.booking.resource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yu030x.booking.common.exception.GlobalExceptionHandler;
import com.yu030x.booking.resource.controller.CategoryController;
import com.yu030x.booking.resource.controller.ResourceController;
import com.yu030x.booking.resource.entity.ResourceCategoryEntity;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import com.yu030x.booking.resource.entity.ResourceEntity;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import com.yu030x.booking.resource.mapper.ResourceCategoryMapper;
import com.yu030x.booking.resource.mapper.ResourceClosureMapper;
import com.yu030x.booking.resource.mapper.ResourceMapper;
import com.yu030x.booking.resource.mapper.ResourceTimeRuleMapper;
import com.yu030x.booking.resource.service.CategoryService;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ResourceBoundaryApiTest {
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private ResourceCategoryMapper categoryMapper;
    @Mock
    private ResourceTimeRuleMapper timeRuleMapper;
    @Mock
    private ResourceClosureMapper closureMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ResourceCatalogService resourceService = new ResourceCatalogService(
                resourceMapper, categoryMapper, timeRuleMapper, closureMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CategoryController(new CategoryService(categoryMapper)),
                        new ResourceController(resourceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
                .build();
    }

    @Test
    void categoryNormalizationDefaultsAndBoundaryOverrunsUse400Envelope() throws Exception {
        doAnswer(invocation -> {
            ResourceCategoryEntity entity = invocation.getArgument(0);
            entity.setId(55L);
            return 1;
        }).when(categoryMapper).insert(any(ResourceCategoryEntity.class));
        when(categoryMapper.selectActiveById(55L)).thenAnswer(invocation -> {
            ResourceCategoryEntity entity = new ResourceCategoryEntity();
            entity.setId(55L);
            entity.setName("Rooms");
            entity.setParentId(0L);
            entity.setSortOrder(0);
            return entity;
        });

        mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType("application/json")
                        .content("{\"name\":\"  Rooms  \",\"icon\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Rooms"))
                .andExpect(jsonPath("$.data.parentId").value("0"))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.icon").value(org.hamcrest.Matchers.nullValue()));
        ArgumentCaptor<ResourceCategoryEntity> categoryCaptor = ArgumentCaptor.forClass(ResourceCategoryEntity.class);
        verify(categoryMapper).insert(categoryCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals("Rooms", categoryCaptor.getValue().getName());
        org.junit.jupiter.api.Assertions.assertEquals(0L, categoryCaptor.getValue().getParentId());
        org.junit.jupiter.api.Assertions.assertEquals(0, categoryCaptor.getValue().getSortOrder());
        org.junit.jupiter.api.Assertions.assertNull(categoryCaptor.getValue().getIcon());

        for (String body : List.of(
                "{\"name\":\"" + "x".repeat(51) + "\"}",
                "{\"name\":\"ok\",\"icon\":\"" + "x".repeat(256) + "\"}",
                "{\"name\":\"ok\",\"sortOrder\":-100001}",
                "{\"name\":\"ok\",\"sortOrder\":100001}")) {
            mockMvc.perform(post("/api/v1/admin/categories")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
    }

    @Test
    void resourceNormalizationAndDefaultsAreReturnedThroughHttp() throws Exception {
        ResourceCategoryEntity category = new ResourceCategoryEntity();
        category.setId(3L);
        when(categoryMapper.selectActiveForUpdate(3L)).thenReturn(category);
        doAnswer(invocation -> {
            ResourceEntity entity = invocation.getArgument(0);
            entity.setId(42L);
            return 1;
        }).when(resourceMapper).insert(any(ResourceEntity.class));
        when(resourceMapper.selectActiveById(42L)).thenAnswer(invocation -> {
            ResourceEntity entity = new ResourceEntity();
            entity.setId(42L);
            entity.setCategoryId(3L);
            entity.setName("Room");
            entity.setDescription("  details  ");
            entity.setNeedApproval(false);
            entity.setMaxAdvanceDays(7);
            entity.setMinDurationMinutes(30);
            entity.setMaxDurationMinutes(120);
            entity.setStatus(1);
            return entity;
        });

        mockMvc.perform(post("/api/v1/admin/resources")
                        .contentType("application/json")
                        .content("""
                                {"categoryId":"3","name":"  Room  ","location":"  ",
                                 "description":"  details  ","images":"  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("42"))
                .andExpect(jsonPath("$.data.categoryId").value("3"))
                .andExpect(jsonPath("$.data.name").value("Room"))
                .andExpect(jsonPath("$.data.location").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.description").value("  details  "))
                .andExpect(jsonPath("$.data.images").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.capacity").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.needApproval").value(false))
                .andExpect(jsonPath("$.data.maxAdvanceDays").value(7))
                .andExpect(jsonPath("$.data.minDurationMinutes").value(30))
                .andExpect(jsonPath("$.data.maxDurationMinutes").value(120))
                .andExpect(jsonPath("$.data.status").value(1));

        ArgumentCaptor<ResourceEntity> captor = ArgumentCaptor.forClass(ResourceEntity.class);
        verify(resourceMapper).insert(captor.capture());
        ResourceEntity normalized = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(3L, normalized.getCategoryId());
        org.junit.jupiter.api.Assertions.assertEquals("Room", normalized.getName());
        org.junit.jupiter.api.Assertions.assertNull(normalized.getLocation());
        org.junit.jupiter.api.Assertions.assertNull(normalized.getImages());
        org.junit.jupiter.api.Assertions.assertNull(normalized.getCapacity());
    }

    @Test
    void documentedResourceOverrunsAndMalformedTimesReturn400Envelope() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"categoryId\":\"1\",\"name\":\"" + "x".repeat(101) + "\"}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"location\":\"" + "x".repeat(201) + "\"}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"images\":\"" + "x".repeat(1001) + "\"}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"description\":\"" + "x".repeat(10001) + "\"}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"capacity\":0}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"maxAdvanceDays\":366}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"minDurationMinutes\":45}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"minDurationMinutes\":120,\"maxDurationMinutes\":60}",
                "{\"categoryId\":\"1\",\"name\":\"ok\",\"status\":3}");

        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/v1/admin/resources")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }

        for (String rules : List.of(
                "[{\"dayOfWeek\":1,\"startTime\":\"08:15:00\",\"endTime\":\"09:00:00\"}]",
                "[{\"dayOfWeek\":1,\"startTime\":\"08:00:30\",\"endTime\":\"09:00:00\"}]",
                "[{\"dayOfWeek\":1,\"startTime\":\"08:00:00\",\"endTime\":\"10:00:00\"},"
                        + "{\"dayOfWeek\":1,\"startTime\":\"09:30:00\",\"endTime\":\"11:00:00\"}]")) {
            mockMvc.perform(put("/api/v1/admin/resources/7/time-rules")
                            .contentType("application/json").content(rules))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(40000))
                    .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
    }

    @Test
    void paginationFiltersStatusAndAdjacentHalfHourRulesAreAccepted() throws Exception {
        ResourceEntity row = activeResource(8L);
        row.setName("Lab 8");
        when(resourceMapper.selectResourcePage(any(Page.class), eq(3L), eq(2), eq("lab")))
                .thenAnswer(invocation -> {
                    Page<ResourceEntity> page = invocation.getArgument(0);
                    page.setTotal(1);
                    page.setRecords(List.of(row));
                    return page;
                });

        mockMvc.perform(get("/api/v1/resources?pageNumber=2&pageSize=2&categoryId=3&status=2&keyword=lab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNumber").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("8"));

        when(resourceMapper.selectActiveForUpdate(7L)).thenReturn(activeResource(7L));
        when(timeRuleMapper.selectActiveByResourceId(7L)).thenReturn(List.of());
        mockMvc.perform(put("/api/v1/admin/resources/7/time-rules")
                        .contentType("application/json")
                        .content("[{\"dayOfWeek\":1,\"startTime\":\"08:00:00\",\"endTime\":\"09:30:00\"},"
                                + "{\"dayOfWeek\":1,\"startTime\":\"09:30:00\",\"endTime\":\"10:00:00\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<ResourceTimeRuleEntity> captor = ArgumentCaptor.forClass(ResourceTimeRuleEntity.class);
        verify(timeRuleMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of(LocalTime.of(8, 0), LocalTime.of(9, 30)),
                captor.getAllValues().stream().map(ResourceTimeRuleEntity::getStartTime).toList());
    }

    @Test
    void closureGlobalResourceDuplicateAndWrongScopeFollowContract() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 20);
        when(resourceMapper.selectActiveById(7L)).thenReturn(activeResource(7L));
        when(closureMapper.selectByScopeAndDate(0L, date)).thenReturn(null);
        when(closureMapper.selectByScopeAndDate(7L, date)).thenReturn(null);
        doAnswer(invocation -> {
            ResourceClosureEntity entity = invocation.getArgument(0);
            entity.setId(entity.getResourceId() == 0L ? 101L : 102L);
            return 1;
        }).when(closureMapper).insert(any(ResourceClosureEntity.class));
        when(closureMapper.selectByIdAndScope(101L, 0L)).thenAnswer(invocation -> closure(101L, 0L, date));
        when(closureMapper.selectByIdAndScope(102L, 7L)).thenAnswer(invocation -> closure(102L, 7L, date));

        mockMvc.perform(post("/api/v1/admin/resources/0/closures")
                        .contentType("application/json")
                        .content("{\"closureDate\":\"2026-08-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value("0"));
        mockMvc.perform(post("/api/v1/admin/resources/7/closures")
                        .contentType("application/json")
                        .content("{\"closureDate\":\"2026-08-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resourceId").value("7"));

        when(closureMapper.selectByScopeAndDate(7L, date)).thenReturn(closure(102L, 7L, date));
        mockMvc.perform(post("/api/v1/admin/resources/7/closures")
                        .contentType("application/json")
                        .content("{\"closureDate\":\"2026-08-20\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(42000))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        when(closureMapper.selectByIdAndScope(101L, 7L)).thenReturn(null);
        mockMvc.perform(delete("/api/v1/admin/resources/7/closures/101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    private ResourceEntity activeResource(long id) {
        ResourceEntity entity = new ResourceEntity();
        entity.setId(id);
        entity.setCategoryId(3L);
        entity.setName("Resource " + id);
        entity.setStatus(1);
        return entity;
    }

    private ResourceClosureEntity closure(long id, long resourceId, LocalDate date) {
        ResourceClosureEntity entity = new ResourceClosureEntity();
        entity.setId(id);
        entity.setResourceId(resourceId);
        entity.setClosureDate(date);
        return entity;
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().addModule(new JavaTimeModule()).build();
    }
}
