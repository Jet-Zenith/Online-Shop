package com.shop.service;

import com.shop.model.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 库存告警服务。
 * <p>
 * 当前项目中用于在订单结算成功后检查商品库存是否低于阈值。
 * 后续如果接入短信、邮件、企业微信或 MQ 告警，也可以从这里扩展。
 */
@Slf4j
@Service
public class InventoryAlertService {

    private final ProductService productService;
    private final int lowStockThreshold;

    /**
     * 创建库存告警服务。
     *
     * @param productService    商品服务，用于查询最新商品库存
     * @param lowStockThreshold 低库存阈值，默认值来自 app.inventory.low-stock-threshold，默认 5
     */
    public InventoryAlertService(ProductService productService,
                                 @Value("${app.inventory.low-stock-threshold:5}") int lowStockThreshold) {
        this.productService = productService;
        this.lowStockThreshold = lowStockThreshold;
    }

    /**
     * 检查指定商品是否达到低库存阈值。
     * <p>
     * 该方法通常在订单事务提交后调用，避免库存扣减或订单创建回滚后产生误告警。
     *
     * @param productId 需要检查库存的商品 ID
     */
    public void checkLowStock(String productId) {
        // 1. 查询商品最新库存。这里会走 ProductService 的缓存/数据库查询逻辑。
        Product product = productService.getProductById(productId);
        if (product != null && product.getStock() <= lowStockThreshold) {
            // 2. 当前先通过日志记录低库存告警；生产环境可替换为 MQ、短信、邮件等通知渠道。
            log.warn("Low stock alert productId={}, name={}, stock={}",
                    product.getId(), product.getName(), product.getStock());
        }
    }
}
