package com.shop.model;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户实体类
 * 对应数据库 user 表，用于接收/封装用户数据
 */
@Data                // Lombok：自动生成 getter/setter/toString/equals/hashCode 等方法
@NoArgsConstructor
@AllArgsConstructor
@Builder             // Lombok：支持建造者模式链式创建对象
@TableName("user")   // MyBatis-Plus：指定该实体类映射的数据库表名
public class User implements Serializable {

    /**
     * 序列化版本号
     * 用于 Java 序列化时保证版本一致性，防止反序列化失败
     */
    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     * MyBatis-Plus：主键注解，使用 ASSIGN_ID 雪花算法自动生成唯一字符串 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String username;//用户名
    private String email;//用户邮箱
    /**
     * 用户密码
     * Json 序列化安全配置：只允许写入（接收前端传参），禁止返回给前端，防止密码泄露
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @TableField(fill = FieldFill.INSERT) // 仅插入时填充
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE) // 插入和更新时均填充
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;
}