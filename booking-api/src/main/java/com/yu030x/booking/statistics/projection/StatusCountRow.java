package com.yu030x.booking.statistics.projection;

/** Raw status/count projection; only frozen statuses survive service mapping. */
public class StatusCountRow {
    private String status;
    private Long count;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCount() { return count; }
    public void setCount(Long count) { this.count = count; }
}
