package com.yu030x.booking.approval.mapper;

import com.yu030x.booking.approval.entity.ApprovalRecordEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface ApprovalRecordMapper {
    @Insert("INSERT INTO approval_record(booking_id,approver_id,action,comment) "
            + "VALUES(#{bookingId},#{approverId},#{action},#{comment})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ApprovalRecordEntity record);
}
