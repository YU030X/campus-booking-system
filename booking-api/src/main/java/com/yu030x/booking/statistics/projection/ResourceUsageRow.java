package com.yu030x.booking.statistics.projection;

/**
 * Raw one-row-per-resource projection of the usage aggregate query. Numeric
 * columns are read as Long so the service can enforce the non-negative
 * invariant and reject any poisoned row instead of leaking it.
 *
 * <p>schedulableMinutes is the summed per-date denominator over the inclusive
 * range (closure days contribute zero); occupiedSlotMinutes applies the frozen
 * booking/slot semantics: every booking_slot row inside the half-open window
 * counts as 30 minutes.</p>
 */
public class ResourceUsageRow {
    private Long resourceId;
    private String resourceName;
    private Long bookingCount;
    private Long completedCount;
    private Long cancelledCount;
    private Long noShowCount;
    private Long occupiedSlotMinutes;
    private Long schedulableMinutes;

    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public Long getBookingCount() { return bookingCount; }
    public void setBookingCount(Long bookingCount) { this.bookingCount = bookingCount; }
    public Long getCompletedCount() { return completedCount; }
    public void setCompletedCount(Long completedCount) { this.completedCount = completedCount; }
    public Long getCancelledCount() { return cancelledCount; }
    public void setCancelledCount(Long cancelledCount) { this.cancelledCount = cancelledCount; }
    public Long getNoShowCount() { return noShowCount; }
    public void setNoShowCount(Long noShowCount) { this.noShowCount = noShowCount; }
    public Long getOccupiedSlotMinutes() { return occupiedSlotMinutes; }
    public void setOccupiedSlotMinutes(Long occupiedSlotMinutes) { this.occupiedSlotMinutes = occupiedSlotMinutes; }
    public Long getSchedulableMinutes() { return schedulableMinutes; }
    public void setSchedulableMinutes(Long schedulableMinutes) { this.schedulableMinutes = schedulableMinutes; }
}
