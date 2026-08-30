package com.yu030x.booking.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yu030x.booking.resource.entity.ResourceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourceEntity> {
    @Select("""
            <script>
            SELECT id,category_id,name,location,capacity,description,images,need_approval,
                   max_advance_days,min_duration_minutes,max_duration_minutes,status,deleted,
                   created_at,updated_at
            FROM resource
            WHERE deleted=0
            <if test="categoryId != null">AND category_id=#{categoryId}</if>
            <if test="status != null">AND status=#{status}</if>
            <if test="keyword != null">AND name LIKE CONCAT('%',#{keyword},'%')</if>
            ORDER BY created_at DESC,id DESC
            </script>
            """)
    IPage<ResourceEntity> selectResourcePage(
            IPage<ResourceEntity> page,
            @Param("categoryId") Long categoryId,
            @Param("status") Integer status,
            @Param("keyword") String keyword);

    @Select("SELECT id,category_id,name,location,capacity,description,images,need_approval,"
            + "max_advance_days,min_duration_minutes,max_duration_minutes,status,deleted,created_at,updated_at "
            + "FROM resource WHERE id=#{id} AND deleted=0")
    ResourceEntity selectActiveById(@Param("id") long id);

    @Select("SELECT id,category_id,name,location,capacity,description,images,need_approval,"
            + "max_advance_days,min_duration_minutes,max_duration_minutes,status,deleted,created_at,updated_at "
            + "FROM resource WHERE id=#{id} AND deleted=0 FOR UPDATE")
    ResourceEntity selectActiveForUpdate(@Param("id") long id);

    @Select("SELECT id FROM resource WHERE deleted=0 AND status=1 ORDER BY id")
    List<Long> selectEnabledIdsForCacheInvalidation();

    @Update("UPDATE resource SET category_id=#{entity.categoryId},name=#{entity.name},"
            + "location=#{entity.location},capacity=#{entity.capacity},description=#{entity.description},"
            + "images=#{entity.images},need_approval=#{entity.needApproval},"
            + "max_advance_days=#{entity.maxAdvanceDays},min_duration_minutes=#{entity.minDurationMinutes},"
            + "max_duration_minutes=#{entity.maxDurationMinutes},status=#{entity.status} "
            + "WHERE id=#{entity.id} AND deleted=0")
    int updateActive(@Param("entity") ResourceEntity entity);

    @Update("UPDATE resource SET status=#{status} WHERE id=#{id} AND deleted=0")
    int updateActiveStatus(@Param("id") long id, @Param("status") int status);

    @Update("UPDATE resource SET deleted=1 WHERE id=#{id} AND deleted=0")
    int logicalDeleteActive(@Param("id") long id);
}
