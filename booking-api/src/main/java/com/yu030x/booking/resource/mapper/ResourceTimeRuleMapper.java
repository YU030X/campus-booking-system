package com.yu030x.booking.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yu030x.booking.resource.entity.ResourceTimeRuleEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceTimeRuleMapper extends BaseMapper<ResourceTimeRuleEntity> {
    @Select("SELECT id,resource_id,day_of_week,start_time,end_time,deleted,created_at "
            + "FROM resource_time_rule WHERE resource_id=#{resourceId} AND deleted=0 "
            + "ORDER BY day_of_week ASC,start_time ASC,end_time ASC,id ASC")
    List<ResourceTimeRuleEntity> selectActiveByResourceId(@Param("resourceId") long resourceId);

    @Update("UPDATE resource_time_rule SET deleted=1 WHERE resource_id=#{resourceId} AND deleted=0")
    int logicalDeleteActiveByResourceId(@Param("resourceId") long resourceId);
}
