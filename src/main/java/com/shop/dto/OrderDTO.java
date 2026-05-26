package com.shop.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class OrderDTO {
    private String id;// 订单主键 ID
    private String orderNo;// 业务订单号
    private String userId;// 下单用户 ID
    private BigDecimal totalAmount;// 订单总金额
    private Integer totalQuantity;// 订单商品总件数
    private String status;//订单状态
    private LocalDateTime createTime;//创建时间
    private List<OrderItemDTO> items;//订单明细实体
}
