package com.shop.model;

/**
 * 订单状态枚举。
 */
public enum OrderStatus {
    CREATED,// 订单已创建，等待后续支付或履约
    PAID,// 订单已支付
    CANCELLED// 订单已取消
}
