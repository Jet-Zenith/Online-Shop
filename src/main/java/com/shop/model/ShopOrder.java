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
@TableName("shop_order")
/**
 * 订单主表实体，保存一笔订单的整体金额、数量、状态和归属用户。
 */
public class ShopOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;// 订单主键 ID，系统内部关联订单明细时使用
    private String orderNo;// 业务订单号，面向用户、客服、支付、对账等业务场景
    private String userId;// 下单用户 ID
    private BigDecimal totalAmount;// 订单总金额
    private Integer totalQuantity;// 订单商品总件数
    private String status;//订单状态，对应 OrderStatus 枚举值

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;//创建时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;// 最近更新时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT)
    private String createUser;// 创建人 ID

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateUser;// 最近更新人 ID
}
