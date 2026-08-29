package com.yu030x.booking.log.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationLogRedactorTest {

    private final OperationLogRedactor redactor = new OperationLogRedactor();

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2VjcmV0c2lnbmF0dXJlMTIz";

    @Test
    void emptyArgsProjectToEmptyArray() {
        assertEquals("[]", redactor.project(new Object[0]));
        assertEquals("[]", redactor.project(null));
    }

    @Test
    void sensitiveKeysAreMaskedAtAnyDepth() {
        Map<String, Object> nested = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put("password", "sup3r-secret");
        inner.put("accessToken", TOKEN);
        nested.put("payload", inner);
        nested.put("authorization", "Bearer abc.def");
        String out = redactor.project(new Object[]{nested});
        assertFalse(out.contains("sup3r-secret"));
        assertFalse(out.contains(TOKEN));
        assertFalse(out.contains("Bearer abc.def"));
        assertTrue(out.contains("***"));
    }

    @Test
    void fullPhoneNumbersAndJwtValuesInsidePlainStringsAreMasked() {
        String out = redactor.project(new Object[]{"call me 13812345678 now", "tok=" + TOKEN});
        assertFalse(out.contains("13812345678"));
        assertTrue(out.contains("138****5678"));
        assertFalse(out.contains(TOKEN));
        assertTrue(out.contains("***"));
    }

    @Test
    void oversizedStringsAreCutToDeterministicBound() {
        String big = "a".repeat(5000);
        String out = redactor.project(new Object[]{big});
        assertFalse(out.contains("a".repeat(501)));
        assertTrue(out.length() < 600);
        Object[] wide = new Object[5];
        for (int i = 0; i < wide.length; i++) {
            wide[i] = "x".repeat(OperationLogRedactor.MAX_STRING);
        }
        String total = redactor.project(wide);
        assertEquals(OperationLogRedactor.MAX_PARAMS_TOTAL, total.length(),
                () -> "over-cap projection must be cut to exactly the cap, was " + total.length());
        assertTrue(total.startsWith("{\"arg0\":"));
        assertTrue(total.endsWith("\"[truncated]"));
    }

    @Test
    void inlineCredentialPairsInPlainTextAreMasked() {
        String leaked = redactor.maskText("connect password=hunter2 now");
        assertFalse(leaked.contains("hunter2"));
        assertTrue(leaked.contains("password=***"));
        String spaced = redactor.maskText("apiKey: s3cr3tKey");
        assertFalse(spaced.contains("s3cr3tKey"));
        assertTrue(spaced.contains("apiKey"));
    }

    @Test
    void jdbcAndRedisUriUserinfoSecretsAreMasked() {
        for (String raw : new String[]{
                "jdbc:mysql://app:p4ssw0rd@db1:3306/shop",
                "redis://default:S3cret@cache:6379/0",
                "rediss://u:TopSecret@cache:6380"}) {
            String masked = redactor.maskText(raw);
            assertFalse(masked.contains("p4ssw0rd"), () -> "leak in " + raw);
            assertFalse(masked.contains("S3cret"), () -> "leak in " + raw);
            assertFalse(masked.contains("TopSecret"), () -> "leak in " + raw);
            assertTrue(masked.contains("***"), () -> "expected mask in " + raw);
        }
    }

    @Test
    void deepNestingHitsDeterministicDepthCap() {
        Object node = "leaf";
        for (int i = 0; i < 20; i++) {
            node = List.of(node);
        }
        String out = redactor.project(new Object[]{node});
        assertTrue(out.contains("[max-depth]") || out.contains("[truncated]"));
    }

    @Test
    void errorMessageIsBoundedMaskedAndIncludesCauseChain() {
        Throwable root = new IllegalStateException("phone 13900001111 leaked");
        Throwable wrapped = new RuntimeException("outer msg=" + TOKEN, root);
        String out = redactor.error(wrapped);
        assertTrue(out.length() <= OperationLogRedactor.MAX_ERROR_MSG);
        assertTrue(out.startsWith("RuntimeException"));
        assertTrue(out.contains("<- IllegalStateException"));
        assertFalse(out.contains(TOKEN));
        assertFalse(out.contains("13900001111"));
        assertNull(redactor.error(null));
    }

    @Test
    void projectionEscapesQuotesAndControlChars() {
        String out = redactor.project(new Object[]{"say \"hi\"\n\t"});
        assertTrue(out.contains("say \\\"hi\\\"\\n\\t"));
    }
}
