package com.shop.config;

import com.shop.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductCacheWarmupJob {

    private final ProductService productService;
    private final boolean enabled;

    public ProductCacheWarmupJob(ProductService productService,
                                 @Value("${app.cache-warmup.enabled:true}") boolean enabled) {
        this.productService = productService;
        this.enabled = enabled;
    }

    @Scheduled(
            initialDelayString = "${app.cache-warmup.initial-delay-ms:30000}",
            fixedDelayString = "${app.cache-warmup.fixed-delay-ms:300000}"
    )
    public void warmupProductCaches() {
        if (!enabled) {
            return;
        }
        try {
            productService.getHotProducts();
            productService.getAllProducts();
            log.info("Product cache warmup finished");
        } catch (RuntimeException ex) {
            log.warn("Product cache warmup skipped: {}", ex.getMessage());
        }
    }
}
