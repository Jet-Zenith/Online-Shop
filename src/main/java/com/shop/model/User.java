package com.shop.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("user")
/**
 * 用户表实体，保存登录账号、密码摘要以及审计字段。
 */
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;// 用户主键 ID，系统内部关联用户数据时使用
    private String username;// 登录用户名
    private String email;// 用户邮箱，可用于联系、登录或找回账号

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;// 密码摘要，只允许请求写入，不会在接口响应中返回

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;// 创建时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;// 最近更新时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT)
    private String createUser;// 创建人 ID，未登录的系统行为默认填充为 system
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;// 最近更新人 ID
}
