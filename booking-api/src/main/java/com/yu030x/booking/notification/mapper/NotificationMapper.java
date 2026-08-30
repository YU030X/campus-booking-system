package com.yu030x.booking.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yu030x.booking.notification.entity.NotificationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * All notification SQL lives in this slice. The {@code user} table is only
 * read for the recipient lock; the user package itself stays untouched.
 */
@Mapper
public interface NotificationMapper extends BaseMapper<NotificationEntity> {

    /** Locks the living recipient row; {@code null} means missing or soft-deleted. */
    @Select("SELECT id FROM `user` WHERE id = #{userId} AND deleted = 0 FOR UPDATE")
    Long lockRecipientById(@Param("userId") long userId);

    /**
     * Null-safe duplicate check: MySQL {@code <=>} makes {@code biz_id} compare
     * equal only to an equally {@code NULL} request value. No unique key exists,
     * so this check plus the row lock above serialize concurrent first deliveries.
     */
    @Select("SELECT COUNT(*) FROM notification "
            + "WHERE user_id = #{userId} AND type = #{type} AND biz_id <=> #{bizId}")
    int countDuplicate(@Param("userId") long userId,
            @Param("type") String type, @Param("bizId") Long bizId);

    /** Owner-scoped read marker; returns 0 for both foreign and missing ids. */
    @Update("UPDATE notification SET is_read = 1 WHERE id = #{id} AND user_id = #{userId}")
    int markRead(@Param("id") long id, @Param("userId") long userId);
}
