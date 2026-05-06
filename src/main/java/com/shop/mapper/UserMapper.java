package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.User;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 空空如也！但你已经拥有了针对 User 表的增删改查、批量操作、分页查询等几十个方法！

}