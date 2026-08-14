package com.yu030x.booking.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.yu030x.booking.common.exception.BizException;
import com.yu030x.booking.common.exception.ErrorCode;
import com.yu030x.booking.resource.dto.ClosureRequest;
import com.yu030x.booking.resource.dto.TimeRuleRequest;
import com.yu030x.booking.resource.service.ResourceCatalogService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt in with RESOURCE_MYSQL_URL/RESOURCE_MYSQL_USERNAME/RESOURCE_MYSQL_PASSWORD against MySQL 8. */
@EnabledIfEnvironmentVariable(named = "RESOURCE_MYSQL_URL", matches = "jdbc:mysql://.+")
@SpringBootTest
class ResourceMysqlIntegrationTest {
    private static final String NAME_PREFIX = "codex-resource-it-";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ResourceCatalogService resourceService;

    private long categoryId;
    private long resourceId;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> System.getenv("RESOURCE_MYSQL_URL"));
        registry.add("DB_USERNAME", () -> System.getenv().getOrDefault("RESOURCE_MYSQL_USERNAME", "root"));
        registry.add("DB_PASSWORD", () -> System.getenv().getOrDefault("RESOURCE_MYSQL_PASSWORD", ""));
    }

    @BeforeEach
    void createFixture() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assumeTrue(version != null && version.startsWith("8."),
                "RESOURCE_MYSQL_URL must point to MySQL 8, got " + version);
        String name = NAME_PREFIX + System.nanoTime();
        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)", name);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                Long.class, name);
        jdbc.update("""
                INSERT INTO resource(category_id,name,need_approval,max_advance_days,
                    min_duration_minutes,max_duration_minutes,status,deleted)
                VALUES (?,?,0,7,30,120,1,0)
                """, categoryId, name);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? AND deleted=0 ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, name);
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM resource_closure WHERE resource_id IN (0,?)", resourceId);
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM resource_category WHERE id=?", categoryId);
    }

    @Test
    void concurrentTimeRuleReplacementsLeaveOneCompleteSet() throws Exception {
        List<TimeRuleRequest> first = List.of(
                new TimeRuleRequest(1, "08:00:00", "09:30:00"),
                new TimeRuleRequest(3, "13:00:00", "14:00:00"));
        List<TimeRuleRequest> second = List.of(
                new TimeRuleRequest(2, "10:00:00", "11:00:00"),
                new TimeRuleRequest(5, "18:00:00", "19:30:00"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = executor.submit(() -> replaceAfterStart(first, ready, start));
            Future<?> right = executor.submit(() -> replaceAfterStart(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            left.get(30, TimeUnit.SECONDS);
            right.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<String> activeRows = jdbc.query(
                "SELECT CONCAT(day_of_week,':',TIME_FORMAT(start_time,'%H:%i:%s'),'-',"
                        + "TIME_FORMAT(end_time,'%H:%i:%s')) FROM resource_time_rule "
                        + "WHERE resource_id=? AND deleted=0 ORDER BY day_of_week,start_time",
                (rs, rowNum) -> rs.getString(1), resourceId);
        List<String> firstRows = List.of("1:08:00:00-09:30:00", "3:13:00:00-14:00:00");
        List<String> secondRows = List.of("2:10:00:00-11:00:00", "5:18:00:00-19:30:00");
        assertTrue(activeRows.equals(firstRows) || activeRows.equals(secondRows), activeRows.toString());
    }

    @Test
    void closureConcurrencyScopeCoexistenceAndPhysicalDeleteAreEnforced() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 1);
        jdbc.update("DELETE FROM resource_closure WHERE resource_id IN (0,?) AND closure_date=?", resourceId, date);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();
        try {
            Future<?> left = executor.submit(() -> createClosureAfterStart(date, start, failures));
            Future<?> right = executor.submit(() -> createClosureAfterStart(date, start, failures));
            start.countDown();
            left.get(30, TimeUnit.SECONDS);
            right.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, failures.size());
        BizException conflict = assertInstanceOf(BizException.class, failures.get(0));
        assertEquals(ErrorCode.RESOURCE_ERROR, conflict.errorCode);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_closure WHERE resource_id=? AND closure_date=?",
                Integer.class, resourceId, date));

        resourceService.createClosure("0", new ClosureRequest(date.toString(), "global"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_closure WHERE closure_date=? AND resource_id IN (0,?)",
                Integer.class, date, resourceId));

        BizException duplicate = org.junit.jupiter.api.Assertions.assertThrows(
                BizException.class,
                () -> resourceService.createClosure(Long.toString(resourceId),
                        new ClosureRequest(date.toString(), null)));
        assertEquals(ErrorCode.RESOURCE_ERROR, duplicate.errorCode);

        LocalDate removable = date.plusDays(1);
        var created = resourceService.createClosure(Long.toString(resourceId),
                new ClosureRequest(removable.toString(), "remove"));
        resourceService.deleteClosure(Long.toString(resourceId), created.id());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_closure WHERE id=?", Integer.class, Long.parseLong(created.id())));
        BizException missing = org.junit.jupiter.api.Assertions.assertThrows(
                BizException.class,
                () -> resourceService.deleteClosure(Long.toString(resourceId), created.id()));
        assertEquals(ErrorCode.NOT_FOUND, missing.errorCode);
    }

    private Object replaceAfterStart(
            List<TimeRuleRequest> rules, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        await(start);
        resourceService.replaceTimeRules(Long.toString(resourceId), rules);
        return null;
    }

    private Object createClosureAfterStart(
            LocalDate date, CountDownLatch start, List<Throwable> failures) {
        await(start);
        try {
            resourceService.createClosure(Long.toString(resourceId),
                    new ClosureRequest(date.toString(), "concurrent"));
        } catch (Throwable failure) {
            synchronized (failures) {
                failures.add(failure);
            }
        }
        return null;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
