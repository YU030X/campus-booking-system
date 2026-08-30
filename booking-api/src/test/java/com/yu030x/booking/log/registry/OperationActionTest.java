package com.yu030x.booking.log.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationActionTest {

    @Test
    void approvedKeysResolveCaseInsensitivelyWithModuleAndOperation() {
        assertEquals(OperationAction.BOOKING_CREATE, OperationAction.byKey("booking_create"));
        assertEquals(OperationAction.AUTH_LOGIN, OperationAction.byKey("AUTH_LOGIN"));
        assertEquals("预约", OperationAction.BOOKING_CREATE.module());
        assertEquals("创建预约", OperationAction.BOOKING_CREATE.operation());
    }

    @Test
    void unknownBlankOrNullKeysAreUnapproved() {
        assertNull(OperationAction.byKey("not_in_registry"));
        assertNull(OperationAction.byKey(""));
        assertNull(OperationAction.byKey(null));
        assertNull(OperationAction.byKey("  "));
    }

    @Test
    void registryKeysAreUniqueAndLowercase() {
        Set<String> seen = new HashSet<>();
        for (OperationAction action : OperationAction.values()) {
            String key = action.key();
            if (!seen.add(key)) {
                throw new AssertionError("duplicate key: " + key);
            }
            assertEquals(key.toLowerCase(), key);
        }
    }
}
