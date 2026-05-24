package com.shop.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("product")
/**
 * 商品表实体，承载商品展示、库存扣减和搜索索引同步所需的数据。
 */
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;// 商品主键 ID
    private String name;// 商品名称
    private String description;// 商品描述
    private BigDecimal price;// 商品当前销售价格
    private int stock;// 当前可售库存数量
    private String category;// 商品分类，用于筛选和搜索

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;// 创建时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;// 最近更新时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT)
    private String createUser;// 创建人 ID

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;// 最近更新人 ID
}
