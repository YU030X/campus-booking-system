package com.yu030x.booking.log.mapper;

import com.yu030x.booking.log.entity.OperationLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** Persistence port for the exact {@code operation_log} columns; id stays database-generated. */
@Mapper
public interface OperationLogMapper {

    @Insert("INSERT INTO `operation_log` "
            + "(`user_id`, `module`, `operation`, `method`, `params`, `ip`, `cost_ms`, `success`, `error_msg`, `created_at`) "
            + "VALUES (#{userId}, #{module}, #{operation}, #{method}, #{params}, #{ip}, #{costMs}, #{success}, #{errorMsg}, #{createdAt})")
    int insert(OperationLogEntity entity);
}
