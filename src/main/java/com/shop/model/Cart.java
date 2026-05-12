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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class Cart implements Serializable {

    private static final long serialVersionUID = 1L;

    private String userId;

    @Builder.Default
    private List<CartItem> items = new ArrayList<>();

    public Cart(String userId) {
        this.userId = userId;
        this.items = new ArrayList<>();
    }

    public BigDecimal getTotalPrice() {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .map(CartItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

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
        if (items == null) {
            return null;
        }
        return items.stream()
                .filter(item -> item.getProduct() != null && item.getProduct().getId().equals(productId))
                .findFirst()
                .orElse(null);
    }
}
