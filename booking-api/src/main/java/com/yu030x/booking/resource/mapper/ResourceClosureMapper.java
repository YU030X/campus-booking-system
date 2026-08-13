package com.yu030x.booking.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yu030x.booking.resource.entity.ResourceClosureEntity;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ResourceClosureMapper extends BaseMapper<ResourceClosureEntity> {
    @Select("SELECT id,resource_id,closure_date,reason,created_at FROM resource_closure "
            + "WHERE resource_id=#{resourceId} AND closure_date=#{closureDate}")
    ResourceClosureEntity selectByScopeAndDate(
            @Param("resourceId") long resourceId,
            @Param("closureDate") LocalDate closureDate);

    @Select("SELECT id,resource_id,closure_date,reason,created_at FROM resource_closure "
            + "WHERE id=#{closureId} AND resource_id=#{resourceId}")
    ResourceClosureEntity selectByIdAndScope(
            @Param("closureId") long closureId,
            @Param("resourceId") long resourceId);

    @Delete("DELETE FROM resource_closure WHERE id=#{closureId} AND resource_id=#{resourceId}")
    int physicalDeleteByIdAndScope(
            @Param("closureId") long closureId,
            @Param("resourceId") long resourceId);
}
