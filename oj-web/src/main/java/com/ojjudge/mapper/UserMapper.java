package com.ojjudge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ojjudge.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
