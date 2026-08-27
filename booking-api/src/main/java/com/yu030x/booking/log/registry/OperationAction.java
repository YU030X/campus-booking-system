package com.yu030x.booking.log.registry;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Arrays;

/** Frozen allowlist of operation-log action keys owned by this slice. */
public enum OperationAction {

    AUTH_LOGIN("认证", "登录"),
    USER_STATUS_UPDATE("用户", "状态变更"),
    RESOURCE_UPDATE("资源", "资源更新"),
    BOOKING_CREATE("预约", "创建预约"),
    BOOKING_APPROVE("预约", "审批通过"),
    BOOKING_REJECT("预约", "审批驳回"),
    BOOKING_CANCEL("预约", "取消预约"),
    BOOKING_CHECK_IN("签到", "签到");

    private static final Map<String, OperationAction> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toMap(OperationAction::key, Function.identity()));

    private final String module;
    private final String operation;

    OperationAction(String module, String operation) {
        this.module = module;
        this.operation = operation;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String module() {
        return module;
    }

    public String operation() {
        return operation;
    }

    /** Returns the approved action for the given key or null when unapproved. */
    public static OperationAction byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return BY_KEY.get(key.trim().toLowerCase(Locale.ROOT));
    }
}
