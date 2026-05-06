package com.shop.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类
 * 对应数据库 product 表，用于封装商品相关业务数据
 */
@Data                // Lombok：自动生成get/set/toString/equals/hashCode等方法
@NoArgsConstructor
@AllArgsConstructor
@Builder             // Lombok：支持建造者模式链式构建对象
@TableName("product")// MyBatis-Plus：指定实体类映射的数据库表名
public class Product implements Serializable {

    /**
     * 序列化版本UID
     * 用于对象缓存（如Redis）、网络传输时保证序列化兼容性
     */
    private static final long serialVersionUID = 1L;

    /**
     * 商品唯一ID
     * 主键：使用雪花算法自动生成字符串类型ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;//商品名称
    private String description;//商品描述
    private BigDecimal price;//商品价格，使用BigDecimal保证金额计算精度，避免浮点型误差
    private int stock;//商品库存数量
    private String category;//商品分类

    @TableField(fill = FieldFill.INSERT) // 仅插入时填充
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE) // 插入和更新时均填充
    private LocalDateTime updateTime;

    @TableField(fill = FieldFill.INSERT)
    private String createUser;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;
}