package com.shop.service;

import com.shop.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class InventoryAlertService {

    private final ProductService productService;
    private final int lowStockThreshold;

    public InventoryAlertService(ProductService productService,
                                 @Value("${app.inventory.low-stock-threshold:5}") int lowStockThreshold) {
        this.productService = productService;
        this.lowStockThreshold = lowStockThreshold;
    }

    public void checkLowStock(String productId) {
        Product product = productService.getProductById(productId);
        if (product != null && product.getStock() <= lowStockThreshold) {
            log.warn("Low stock alert productId={}, name={}, stock={}",
                    product.getId(), product.getName(), product.getStock());
        }
    }
}
