package com.shop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 购物车实体类
 * 对应一个用户的完整购物车：包含用户ID + 购物车商品列表
 * 支持序列化存入 Redis，JSON 序列化返回前端
 */
@Data                 // Lombok：自动生成 getter/setter/toString/equals/hashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder              // Lombok：建造者模式，链式创建对象
@JsonIgnoreProperties(ignoreUnknown = true)  // 忽略未知字段，避免反序列化报错
public class Cart implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;//用户ID，关联User.id

    // 使用 @Builder.Default 保证通过 Builder 创建时，List 也被正确初始化，防止空指针
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    public Cart(String userId) {
        this.userId = userId;
        this.items = new ArrayList<>(); // 确保新创建的购物车 items 不为 null
    }
    /**
     * 动态计算购物车总价，彻底移除 totalPrice 物理字段和 calculateTotal() 方法。
     * 完美复用 CartItem 的 getSubtotal() 方法。
     */
    public BigDecimal getTotalPrice() {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(CartItem::getSubtotal) // 拿到每个条目动态计算出的小计
                .reduce(BigDecimal.ZERO, BigDecimal::add); // 累加求和
    }

    // --- 下面是业务逻辑方法，去掉了所有冗余的 calculateTotal() 调用 ---


    public void addItem(Product product, int quantity) {
        if (product == null || quantity <= 0) {
            return;
        }

        CartItem existingItem = findItemByProductId(product.getId());
        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + quantity);
        } else {
            items.add(new CartItem(product, quantity));
        }
    }

    public void removeItem(String productId) {
        if (items != null) {
            items.removeIf(item -> item.getProduct() != null && item.getProduct().getId().equals(productId));
        }
    }

    public void updateItemQuantity(String productId, int quantity) {
        CartItem item = findItemByProductId(productId);
        if (item != null) {
            if (quantity <= 0) {
                removeItem(productId);
            } else {
                item.setQuantity(quantity);
            }
        }
    }

    public void clearCart() {
        if (items != null) {
            items.clear();
        }
    }

    private CartItem findItemByProductId(String productId) {
        if (items == null) return null;
        return items.stream()
                .filter(item -> item.getProduct() != null && item.getProduct().getId().equals(productId))
                .findFirst()
                .orElse(null);
    }
}