package com.shop.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("order_item")
/**
 * 订单明细实体，保存订单中每个商品的价格快照和购买数量。
 */
public class OrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;// 订单明细主键 ID

    private String orderId;// 所属订单 ID，对应 shop_order.id
    private String productId;// 商品 ID，对应 product.id
    private String productName;// 下单时的商品名称快照，防止商品改名影响历史订单展示
    private BigDecimal unitPrice;// 下单时的商品单价快照，防止后续改价影响历史订单金额
    private Integer quantity;// 购买数量
    private BigDecimal subtotal;// 当前明细小计，通常为 unitPrice * quantity
}
