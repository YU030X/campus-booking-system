package com.yu030x.booking.violation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("violation_record")
public class ViolationRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long bookingId;
    private String violationType;
    private Integer scoreChange;
    private String remark;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getViolationType() { return violationType; }
    public void setViolationType(String violationType) { this.violationType = violationType; }
    public Integer getScoreChange() { return scoreChange; }
    public void setScoreChange(Integer scoreChange) { this.scoreChange = scoreChange; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
