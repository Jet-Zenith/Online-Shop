package com.shop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车项实体类
 * 用于封装购物车中的单个商品信息（商品对象 + 购买数量）
 * 支持序列化存入Redis，支持JSON序列化返回给前端
 */
@Data                // Lombok：自动生成get/set/toString/equals/hashCode等方法
@NoArgsConstructor
@AllArgsConstructor
@Builder             // Lombok：支持建造者模式链式构建对象
@JsonIgnoreProperties(ignoreUnknown = true) // JSON序列化：忽略未知字段，防止反序列化报错
public class CartItem implements Serializable {

    /**
     * 序列化版本号
     * 保证对象存入Redis、网络传输时的序列化兼容性
     */
    private static final long serialVersionUID = 1L;
    private Product product;//购物车中的商品对象，关联Product
    private int quantity;//商品购买数量

    /**
     * 动态计算当前购物车项的小计金额
     * 【无数据库物理字段，纯计算属性】
     * 规则：商品单价 × 购买数量
     * 特性：Jackson序列化时会自动识别get方法，生成subtotal字段返回给前端
     * @return 小计金额（异常/空值时返回0）
     */
    public BigDecimal getSubtotal() {
        // 非空校验 + 数量合法性校验
        if (product != null && product.getPrice() != null && quantity > 0) {
            // 计算：单价 × 数量
            return product.getPrice().multiply(BigDecimal.valueOf(quantity));
        }
        // 异常场景返回0
        return BigDecimal.ZERO;
    }
}