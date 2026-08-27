package com.yu030x.booking.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Update("UPDATE `user` SET credit_score = GREATEST(0, credit_score + #{scoreChange}), "
            + "updated_at = #{now} WHERE id = #{userId} AND deleted = 0")
    int applyCreditScoreChange(@Param("userId") long userId,
            @Param("scoreChange") int scoreChange, @Param("now") LocalDateTime now);
}
