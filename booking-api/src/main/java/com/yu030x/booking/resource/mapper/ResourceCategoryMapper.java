package com.yu030x.booking.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yu030x.booking.resource.entity.ResourceCategoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceCategoryMapper extends BaseMapper<ResourceCategoryEntity> {
    @Select("SELECT id,name,parent_id,sort_order,icon,deleted,created_at,updated_at "
            + "FROM resource_category WHERE deleted=0 ORDER BY sort_order ASC,name ASC,id ASC")
    List<ResourceCategoryEntity> selectActiveTreeRows();

    @Select("SELECT id,name,parent_id,sort_order,icon,deleted,created_at,updated_at "
            + "FROM resource_category WHERE id=#{id} AND deleted=0")
    ResourceCategoryEntity selectActiveById(@Param("id") long id);

    @Select("SELECT id,name,parent_id,sort_order,icon,deleted,created_at,updated_at "
            + "FROM resource_category WHERE id=#{id} AND deleted=0 FOR UPDATE")
    ResourceCategoryEntity selectActiveForUpdate(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM resource_category WHERE parent_id=#{parentId} AND deleted=0")
    long countActiveChildren(@Param("parentId") long parentId);

    @Select("SELECT COUNT(*) FROM resource WHERE category_id=#{categoryId} AND deleted=0")
    long countActiveResources(@Param("categoryId") long categoryId);

    @Update("UPDATE resource_category SET name=#{entity.name},parent_id=#{entity.parentId},"
            + "sort_order=#{entity.sortOrder},icon=#{entity.icon} WHERE id=#{entity.id} AND deleted=0")
    int updateActive(@Param("entity") ResourceCategoryEntity entity);

    @Update("UPDATE resource_category SET deleted=1 WHERE id=#{id} AND deleted=0")
    int logicalDeleteActive(@Param("id") long id);
}
