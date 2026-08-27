package com.yu030x.booking.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yu030x.booking.approval.dto.ApproveRequest;
import com.yu030x.booking.approval.dto.CancelRequest;
import com.yu030x.booking.approval.dto.RejectRequest;
import org.junit.jupiter.api.Test;

class ApprovalRequestTest {

    @Test
    void approveCommentTrimsAndMapsBlankToNull() {
        assertThat(new ApproveRequest("  设备完好  ").comment()).isEqualTo("设备完好");
        assertThat(new ApproveRequest("   ").comment()).isNull();
        assertThat(new ApproveRequest(null).comment()).isNull();
        assertThat(new ApproveRequest("").comment()).isNull();
    }

    @Test
    void approveCommentAllowsExactlyFiveHundredCodePointsIncludingAstral() {
        String astral = "😀".repeat(250);
        assertThat(astral.codePointCount(0, astral.length())).isEqualTo(250);
        assertThat(astral.length()).isEqualTo(500);
        assertThat(new ApproveRequest(astral).comment()).isEqualTo(astral);
    }

    @Test
    void approveCommentRejectsOverFiveHundredCodePoints() {
        String overflow = "😀".repeat(501);
        assertThat(overflow.codePointCount(0, overflow.length())).isEqualTo(501);
        assertThatThrownBy(() -> new ApproveRequest(overflow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
        assertThatThrownBy(() -> new ApproveRequest("a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ApproveRequest("a".repeat(500)).comment()).hasSize(500);
    }

    @Test
    void rejectCommentIsRequiredAndTrimmed() {
        assertThatThrownBy(() -> new RejectRequest(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> new RejectRequest("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RejectRequest(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RejectRequest("  不符合使用规范 \n").comment()).isEqualTo("不符合使用规范");
    }

    @Test
    void rejectCommentEnforcesExactlyFiveHundredCodePoints() {
        assertThat(new RejectRequest("a".repeat(500)).comment()).hasSize(500);
        assertThatThrownBy(() -> new RejectRequest("a".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelReasonTrimsAndMapsBlankToNull() {
        assertThat(new CancelRequest("  行程有变  ").cancelReason()).isEqualTo("行程有变");
        assertThat(new CancelRequest(" ").cancelReason()).isNull();
        assertThat(new CancelRequest(null).cancelReason()).isNull();
    }

    @Test
    void cancelReasonEnforcesExactlyTwoHundredCodePointsWithAstralBoundary() {
        String boundary = "😀".repeat(100);
        assertThat(boundary.codePointCount(0, boundary.length())).isEqualTo(100);
        assertThat(boundary.length()).isEqualTo(200);
        assertThat(new CancelRequest(boundary).cancelReason()).isEqualTo(boundary);
        assertThatThrownBy(() -> new CancelRequest("a".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CancelRequest("a".repeat(200)).cancelReason()).hasSize(200);
    }

    @Test
    void unknownJsonFieldsAreRejectedOnEveryRequestDto() {
        assertThatThrownBy(() -> rejectUnknown(new ApproveRequest(null), "status", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown field: status");
        assertThatThrownBy(() -> rejectUnknown(new RejectRequest("ok"), "bookingId", "9"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rejectUnknown(new CancelRequest(null), "reason", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void rejectUnknown(Object request, String name, Object value) {
        if (request instanceof ApproveRequest approve) {
            approve.rejectUnknownField(name, value);
        } else if (request instanceof RejectRequest reject) {
            reject.rejectUnknownField(name, value);
        } else {
            ((CancelRequest) request).rejectUnknownField(name, value);
        }
    }
}
