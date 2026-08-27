package com.yu030x.booking.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.BookingApplication;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-API evidence for task 4.5: all four endpoints through the running
 * server, the real security chain, and a real MySQL 8 schema. Requires
 * BOOKING_MYSQL8_TEST=true plus RESOURCE_MYSQL_URL/DB_* variables; missing
 * variables surface as an explicit failure, never a skip.
 */
@SpringBootTest(classes = BookingApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "booking.identity.enabled=true",
                "booking.security.jwt-secret=0123456789abcdef0123456789abcdef",
                "springdoc.api-docs.enabled=false",
                "springdoc.swagger-ui.enabled=false"
        })
class ApprovalApiRealIntegrationTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "codex-approval-api-it-";
    private static final String PASSWORD = "Password123!";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private long categoryId;
    private long resourceId;
    private String fixtureName;
    private LocalDate date;
    private String adminUsername;
    private String studentUsername;
    private String otherUsername;
    private final List<Long> bookingIds = new ArrayList<>();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> env("RESOURCE_MYSQL_URL", "DB_URL"));
        registry.add("DB_USERNAME", () -> env("RESOURCE_MYSQL_USERNAME", "DB_USERNAME"));
        registry.add("DB_PASSWORD", () -> env("RESOURCE_MYSQL_PASSWORD", "DB_PASSWORD"));
    }

    private static String env(String preferred, String fallback) {
        String value = System.getenv(preferred);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallback);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required MySQL environment variable: " + preferred
                    + " or " + fallback);
        }
        return value;
    }

    @BeforeEach
    void createFixture() {
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        assertThat(Integer.parseInt(version.replaceFirst("^(\\d+).*", "$1"))).isGreaterThanOrEqualTo(8);
        date = LocalDate.now(SHANGHAI).plusDays(10);
        fixtureName = PREFIX + System.nanoTime();
        adminUsername = fixtureName + "-admin";
        studentUsername = fixtureName + "-student";
        otherUsername = fixtureName + "-other";

        jdbc.update("INSERT INTO resource_category(name,parent_id,sort_order,deleted) VALUES (?,0,0,0)",
                fixtureName);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM resource_category WHERE name=? ORDER BY id DESC LIMIT 1",
                Long.class, fixtureName);
        jdbc.update("INSERT INTO resource(category_id,name,need_approval,max_advance_days,"
                        + "min_duration_minutes,max_duration_minutes,status,deleted) "
                        + "VALUES (?,?,1,30,30,240,1,0)", categoryId, fixtureName);
        resourceId = jdbc.queryForObject(
                "SELECT id FROM resource WHERE category_id=? AND name=? ORDER BY id DESC LIMIT 1",
                Long.class, categoryId, fixtureName);
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) "
                        + "VALUES (?,?,?,?,1,0)", adminUsername,
                passwordEncoder.encode(PASSWORD), "管理员", "ADMIN");
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) "
                        + "VALUES (?,?,?,?,1,0)", studentUsername,
                passwordEncoder.encode(PASSWORD), "学生甲", "STUDENT");
        jdbc.update("INSERT INTO `user`(username,password,real_name,role,status,deleted) "
                        + "VALUES (?,?,?,?,1,0)", otherUsername,
                passwordEncoder.encode(PASSWORD), "学生乙", "STUDENT");
    }

    @AfterEach
    void removeFixture() {
        jdbc.update("DELETE FROM approval_record WHERE booking_id IN "
                + "(SELECT id FROM booking WHERE user_id IN "
                + "(SELECT id FROM `user` WHERE username LIKE ?))", fixtureName + "%");
        jdbc.update("DELETE FROM violation_record WHERE user_id IN "
                + "(SELECT id FROM `user` WHERE username LIKE ?)", fixtureName + "%");
        jdbc.update("DELETE FROM violation_record WHERE booking_id IN "
                + "(SELECT id FROM booking WHERE user_id IN "
                + "(SELECT id FROM `user` WHERE username LIKE ?))", fixtureName + "%");
        jdbc.update("DELETE FROM booking_slot WHERE booking_id IN "
                + "(SELECT id FROM booking WHERE user_id IN "
                + "(SELECT id FROM `user` WHERE username LIKE ?))", fixtureName + "%");
        jdbc.update("DELETE FROM booking WHERE user_id IN "
                + "(SELECT id FROM `user` WHERE username LIKE ?)", fixtureName + "%");
        jdbc.update("DELETE FROM `user` WHERE username LIKE ?", fixtureName + "%");
        jdbc.update("DELETE FROM resource_time_rule WHERE resource_id=?", resourceId);
        jdbc.update("DELETE FROM resource WHERE id=?", resourceId);
        jdbc.update("DELETE FROM resource_category WHERE id=?", categoryId);
    }

    private String login(String username) {
        ResponseEntity<Map> response = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", username, "password", PASSWORD), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        return (String) data.get("token");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private long insertBooking(String status, LocalDateTime start, long userId) {
        jdbc.update("INSERT INTO booking(booking_no,user_id,resource_id,start_time,end_time,purpose,"
                        + "attendee_count,status,deleted) VALUES (?,?,?,?,?,NULL,2,?,0)",
                "BR" + Long.toString(System.nanoTime(), Character.MAX_RADIX),
                userId, resourceId, start, start.plusMinutes(60), status);
        long id = jdbc.queryForObject(
                "SELECT id FROM booking WHERE user_id=? AND status=? ORDER BY id DESC LIMIT 1",
                Long.class, userId, status);
        jdbc.update("INSERT INTO booking_slot(resource_id,slot_time,booking_id) "
                        + "SELECT resource_id,start_time,id FROM booking WHERE id=?", id);
        bookingIds.add(id);
        return id;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void allFourEndpointsBehaveThroughRealSecurityChainAndDatabase() {
        String adminToken = login(adminUsername);
        String studentToken = login(studentUsername);
        String otherToken = login(otherUsername);

        long ownerId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",
                Long.class, studentUsername);
        long bookingId = insertBooking("PENDING_APPROVAL",
                LocalDateTime.of(date, LocalTime.of(14, 0)), ownerId);

        ResponseEntity<Map> forbidden = rest.exchange("/api/v1/admin/approvals", HttpMethod.GET,
                new HttpEntity<>(bearer(studentToken)), Map.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody().get("code")).isEqualTo(40300);

        ResponseEntity<Map> list = rest.exchange(
                "/api/v1/admin/approvals?pageNumber=1&pageSize=100", HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> page = (Map<String, Object>) list.getBody().get("data");
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) (Object) page.getOrDefault("records", List.of());
        boolean containsPending = records.stream().anyMatch(record ->
                String.valueOf(record.get("id")).equals(String.valueOf(bookingId)));
        assertThat(containsPending).isTrue();
        records.forEach(record -> {
            assertThat(String.valueOf(record.get("id"))).doesNotContain("[");
            assertThat(record.get("status")).isEqualTo("PENDING_APPROVAL");
        });

        ResponseEntity<Map> unknownField = rest.exchange(
                "/api/v1/admin/bookings/" + bookingId + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of("comment", "ok", "status", "CONFIRMED"),
                        bearer(adminToken)), Map.class);
        assertThat(unknownField.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownField.getBody().get("code")).isEqualTo(40000);

        ResponseEntity<Map> approved = rest.exchange(
                "/api/v1/admin/bookings/" + bookingId + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of("comment", "   "), bearer(adminToken)), Map.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> view = (Map<String, Object>) approved.getBody().get("data");
        assertThat(view.get("status")).isEqualTo("CONFIRMED");
        assertThat(view.get("id")).isEqualTo(String.valueOf(bookingId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_record WHERE booking_id=?",
                Long.class, bookingId)).isEqualTo(1L);
        Integer slots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_slot WHERE booking_id=?", Integer.class, bookingId);
        assertThat(slots).isEqualTo(1);

        ResponseEntity<Map> repeatedApprove = rest.exchange(
                "/api/v1/admin/bookings/" + bookingId + "/approve", HttpMethod.POST,
                new HttpEntity<>(Map.of("comment", "second"), bearer(adminToken)), Map.class);
        assertThat(repeatedApprove.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_record WHERE booking_id=?",
                Long.class, bookingId)).isEqualTo(1L);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void studentCancelMasksForeignBookingsAndReleasesSlots() {
        String studentToken = login(studentUsername);
        String otherToken = login(otherUsername);
        long ownerId = jdbc.queryForObject("SELECT id FROM `user` WHERE username=?",
                Long.class, studentUsername);
        long bookingId = insertBooking("CONFIRMED",
                LocalDateTime.now(SHANGHAI).plusHours(4), ownerId);

        HttpHeaders headers = bearer(studentToken);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map> cancelled = rest.exchange(
                "/api/v1/bookings/" + bookingId + "/cancel", HttpMethod.POST,
                new HttpEntity<>("{\"cancelReason\":\"行程有变\"}", headers), Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> view = (Map<String, Object>) cancelled.getBody().get("data");
        assertThat(view.get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM booking_slot WHERE booking_id=?",
                Long.class, bookingId)).isEqualTo(0L);

        long secondBooking = insertBooking("CONFIRMED",
                LocalDateTime.now(SHANGHAI).plusHours(5), ownerId);
        HttpHeaders otherHeaders = bearer(otherToken);
        otherHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<Map> foreign = rest.exchange(
                "/api/v1/bookings/" + secondBooking + "/cancel", HttpMethod.POST,
                new HttpEntity<>("{}", otherHeaders), Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().get("code")).isEqualTo(40400);
        assertThat(foreign.getBody().get("data")).isNull();

        ResponseEntity<Map> missing = rest.exchange("/api/v1/bookings/999999999/cancel",
                HttpMethod.POST, new HttpEntity<>("{}", headers), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code")).isEqualTo(40400);
    }
}
