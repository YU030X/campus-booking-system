package com.yu030x.booking.violation;

import static org.assertj.core.api.Assertions.assertThat;

import com.yu030x.booking.task.NoShowItemProcessor;
import com.yu030x.booking.task.config.TaskSchedulingConfiguration;
import com.yu030x.booking.violation.port.DefaultViolationPort;
import com.yu030x.booking.violation.port.ViolationPort;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T10 consumer contract for the REQUIRED ViolationPort handed to T09: frozen
 * score constants, no self-opened transaction (REQUIRED participation), and a
 * per-item REQUIRES_NEW processor boundary.
 */
class ViolationPortConsumerContractTest {

    @Test
    void portExposesTheFrozenScoreConstants() {
        assertThat(ViolationPort.NO_SHOW_SCORE_CHANGE).isEqualTo(-10);
        assertThat(ViolationPort.LATE_CANCEL_SCORE_CHANGE).isEqualTo(-5);
    }

    @Test
    void portImplementationJoinsCallerTransactionWithoutOpeningNewOnes() {
        for (Method method : DefaultViolationPort.class.getDeclaredMethods()) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional)
                    .as("ViolationPort methods must not declare their own transaction: %s", method)
                    .isNull();
        }
    }

    @Test
    void noShowProcessorOwnsEachItemWithRequiresNew() throws Exception {
        Method process = NoShowItemProcessor.class.getMethod("process", long.class, long.class);
        Transactional transactional = process.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void scanTaskRunsAtMostOncePerMinuteThroughSpringScheduling() throws Exception {
        assertThat(TaskSchedulingConfiguration.class.getAnnotation(EnableScheduling.class))
                .isNotNull();
        Method scan = com.yu030x.booking.task.NoShowScanTask.class.getMethod("scan");
        Scheduled scheduled = scan.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(60_000L);
    }
}
