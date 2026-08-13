package com.yu030x.booking.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("`user`")
public class User {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String password;
    public String realName;
    public String studentNo;
    public String phone;
    public String email;
    public String avatar;
    public UserRole role;
    public Integer creditScore;
    public Integer status;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    public String getUsername() {
        return username;
    }
}
