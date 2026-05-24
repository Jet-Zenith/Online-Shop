package com.shop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
/**
 * 购物车商品项，表示用户购物车里某个商品及其数量。
 */
public class CartItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private Product product;// 商品对象快照，用于展示名称、价格等信息。
    private int quantity;// 当前商品在购物车中的数量。

    /**
     * 当前商品项小计，通常为商品价格 * 数量。
     */
    public BigDecimal getSubtotal() {
        if (product != null && product.getPrice() != null && quantity > 0) {
            return product.getPrice().multiply(BigDecimal.valueOf(quantity));
        }
        return BigDecimal.ZERO;
    }
}
