package com.yu030x.booking.log.redact;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic bounded sanitizer for operation-log payloads. Sensitive keys,
 * embedded JWTs/Bearer values and full phone numbers are masked; nested
 * structures, string lengths and the final projection size have hard caps so a
 * bounded, secret-free value is always persisted.
 */
public final class OperationLogRedactor {

    static final int MAX_DEPTH = 6;
    static final int MAX_ITEMS = 50;
    static final int MAX_FIELDS = 20;
    static final int MAX_STRING = 500;
    static final int MAX_PARAMS_TOTAL = 2000;
    static final int MAX_ERROR_MSG = 1000;

    private static final String MASK = "***";
    private static final String TRUNCATED_SUFFIX = "\"[truncated]";
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "password|passwd|pwd|secret|token|authorization|credential|apikey|api[_-]?key|access[_-]?key|jwt|bearer",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_CREDENTIAL_PAIR = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|credential|credentials|apikey|api[_-]?key"
                    + "|access[_-]?key|authorization|jwt|bearer)[\\w.\\-]{0,24}\\s*[:=]\\s*"
                    + "(\"[^\"]*\"|[^\\s,&;\"'\\\\}<>]{1,256})");
    private static final Pattern URI_USERINFO_SECRET = Pattern.compile(
            "(?i)((?:jdbc:[a-z0-9]+|rediss?|postgres(?:ql)?|mysql)://[^:/?#\\s@]+:)([^@/?#\\s]{1,256})(@)");
    private static final Pattern JWT_VALUE =
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}");
    private static final Pattern BEARER_PREFIX = Pattern.compile("(?i)^\\s*bearer\\s+\\S+");
    private static final Pattern FULL_PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");

    /** Projects invocation arguments into a bounded JSON-like string (never null). */
    public String project(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        int limit = Math.min(args.length, MAX_ITEMS);
        for (int i = 0; i < limit; i++) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape("arg" + i)).append("\":");
            serialize(sanitize(args[i], 0), sb);
        }
        if (args.length > limit) {
            sb.append(",\"overflow\":\"").append(args.length - limit).append(" more args\"");
        }
        sb.append('}');
        return bound(sb.toString(), MAX_PARAMS_TOTAL, true);
    }

    /** Bounded, masked error projection for {@code error_msg}; never exceeds 1000 chars. */
    public String error(Throwable error) {
        if (error == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 3) {
            if (!sb.isEmpty()) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                sb.append(": ").append(maskText(fits(message, MAX_STRING)));
            }
            current = current.getCause() == current ? null : current.getCause();
            depth++;
        }
        return fits(bound(sb.toString(), MAX_ERROR_MSG, false), MAX_ERROR_MSG);
    }

    /** Deterministic hard truncation helper reused across fields. */
    public static String fits(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** Masks inline key=value credentials, URI userinfo secrets, JWTs, bearer prefixes and full phones. */
    public String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replaceAll(text, INLINE_CREDENTIAL_PAIR,
                m -> m.group(1) + "=***");
        out = replaceAll(out, URI_USERINFO_SECRET,
                m -> m.group(1) + MASK + m.group(3));
        if (BEARER_PREFIX.matcher(out).find()) {
            out = BEARER_PREFIX.matcher(out).replaceAll(MASK);
        }
        out = replaceAll(out, JWT_VALUE, m -> MASK);
        out = replaceAll(out, FULL_PHONE, m ->
                m.group().substring(0, 3) + "****" + m.group().substring(7));
        return out;
    }

    private Object sanitize(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return "[max-depth]";
        }
        if (value instanceof String s) {
            return maskText(fits(s, MAX_STRING));
        }
        if (value instanceof Boolean || value instanceof Number
                || value instanceof Character || value instanceof Date || value instanceof Temporal) {
            return String.valueOf(value);
        }
        if (value instanceof Optional<?> optional) {
            return sanitize(optional.orElse(null), depth + 1);
        }
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap(map, depth);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>();
            int limit = Math.min(collection.size(), MAX_ITEMS);
            int seen = 0;
            for (Object item : collection) {
                if (seen >= limit) {
                    break;
                }
                out.add(sanitize(item, depth + 1));
                seen++;
            }
            if (collection.size() > limit) {
                out.add("[truncated-items:" + (collection.size() - limit) + "]");
            }
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            int limit = Math.min(length, MAX_ITEMS);
            for (int i = 0; i < limit; i++) {
                out.add(sanitize(java.lang.reflect.Array.get(value, i), depth + 1));
            }
            if (length > limit) {
                out.add("[truncated-items:" + (length - limit) + "]");
            }
            return out;
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Class<?>) {
            return ((Class<?>) value).getName();
        }
        if (value instanceof Throwable t) {
            return t.getClass().getSimpleName();
        }
        return sanitizeBean(value, depth);
    }

    private Object sanitizeMap(Map<?, ?> map, int depth) {
        Map<Object, Object> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_FIELDS) {
                out.put("[overflow]", (map.size() - count) + " more entries");
                break;
            }
            Object rawKey = entry.getKey();
            String key = String.valueOf(rawKey == null ? "null" : rawKey);
            if (isSensitiveKey(key)) {
                out.put(key, MASK);
            } else {
                out.put(fits(String.valueOf(rawKey), MAX_STRING), sanitize(entry.getValue(), depth + 1));
            }
            count++;
        }
        return out;
    }

    private Object sanitizeBean(Object bean, int depth) {
        Class<?> type = bean.getClass();
        if ("com.sun.proxy".equals(type.getPackage() != null ? type.getPackage().getName() : "")
                || type.isSynthetic()) {
            return type.getSimpleName();
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        Field[] fields = type.getDeclaredFields();
        int count = 0;
        for (Field field : fields) {
            if (count >= MAX_FIELDS) {
                out.put("[overflow]", (fields.length - count) + " more fields");
                break;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                String name = field.getName();
                if (isSensitiveKey(name)) {
                    out.put(name, MASK);
                } else {
                    Object read = field.get(bean);
                    out.put(name, read == bean ? "[self]" : sanitize(read, depth + 1));
                }
                count++;
            } catch (Throwable ignored) {
                out.put(fits(field.getName(), MAX_STRING), "[unreadable]");
            }
        }
        if (out.isEmpty()) {
            return type.getSimpleName();
        }
        return out;
    }

    private void serialize(Object node, StringBuilder sb) {
        if (node == null) {
            sb.append("null");
            return;
        }
        if (node instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
            return;
        }
        if (node instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                serialize(item, sb);
            }
            sb.append(']');
            return;
        }
        if (node instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                serialize(entry.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        sb.append('"').append(escape(String.valueOf(node))).append('"');
    }

    private String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Cuts to exactly {@code maxLength} when over: at most
     * {@code maxLength - suffix.length()} original chars plus the bounded
     * {@code [truncated]} marker (which re-opens a quote to stay JSON-ish).
     * The returned length never exceeds {@code maxLength}; determinism holds.
     */
    private static String bound(String value, int maxLength, boolean jsonish) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (!jsonish) {
            return value.substring(0, maxLength);
        }
        int suffixLength = TRUNCATED_SUFFIX.length();
        if (maxLength <= suffixLength) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - suffixLength) + TRUNCATED_SUFFIX;
    }

    private static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).find();
    }

    private static String replaceAll(String input, Pattern pattern, java.util.function.Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
