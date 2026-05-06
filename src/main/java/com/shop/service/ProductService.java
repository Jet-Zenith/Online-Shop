package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.mapper.ProductMapper;
import com.shop.model.Product;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ProductService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductMapper productMapper;

    private static final String PRODUCT_KEY_PREFIX = "product:";
    private static final String ALL_PRODUCTS_KEY = "all:products";
    private static final String HOT_PRODUCTS_KEY = "hot:products";
    private static final long PRODUCT_CACHE_TTL_HOURS = 1;
    private static final long HOT_PRODUCTS_TTL_MINUTES = 30;

    public ProductService(RedisTemplate<String, Object> redisTemplate, ProductMapper productMapper) {
        this.redisTemplate = redisTemplate;
        this.productMapper = productMapper;
    }

    public Product createProduct(Product product) {
        productMapper.insert(product);
        redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + product.getId(), product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        evictListCaches();
        return product;
    }

    public Product updateProduct(String productId, Product product) {
        product.setId(productId);
        productMapper.updateById(product);
        redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + productId, product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        evictListCaches();
        return product;
    }

    public boolean deleteProduct(String productId) {
        int rows = productMapper.deleteById(productId);
        if (rows > 0) {
            redisTemplate.delete(PRODUCT_KEY_PREFIX + productId);
            evictListCaches();
            return true;
        }
        return false;
    }

    public Product getProductById(String productId) {
        Product product = (Product) redisTemplate.opsForValue().get(PRODUCT_KEY_PREFIX + productId);
        if (product != null) {
            return product;
        }

        product = productMapper.selectById(productId);
        if (product != null) {
            redisTemplate.opsForValue().set(PRODUCT_KEY_PREFIX + productId, product, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        }
        return product;
    }

    public List<Product> getAllProducts() {
        @SuppressWarnings("unchecked")
        List<Product> products = (List<Product>) redisTemplate.opsForValue().get(ALL_PRODUCTS_KEY);
        if (products != null) {
            return products;
        }

        products = productMapper.selectList(null);
        redisTemplate.opsForValue().set(ALL_PRODUCTS_KEY, products, PRODUCT_CACHE_TTL_HOURS, TimeUnit.HOURS);
        return products;
    }

    public List<Product> getHotProducts() {
        @SuppressWarnings("unchecked")
        List<Product> hotProducts = (List<Product>) redisTemplate.opsForValue().get(HOT_PRODUCTS_KEY);
        if (hotProducts != null) {
            return hotProducts;
        }

        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(Product::getStock).last("limit 5");

        hotProducts = productMapper.selectList(wrapper);
        redisTemplate.opsForValue().set(HOT_PRODUCTS_KEY, hotProducts, HOT_PRODUCTS_TTL_MINUTES, TimeUnit.MINUTES);
        return hotProducts;
    }

    public List<Product> searchProducts(String keyword, String category) {
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(i -> i.like(Product::getName, keyword)
                    .or()
                    .like(Product::getDescription, keyword));
        }

        if (StringUtils.isNotBlank(category)) {
            wrapper.eq(Product::getCategory, category);
        }

        return productMapper.selectList(wrapper);
    }

    public Page<Product> getProductsByPage(int pageNum, int pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        return productMapper.selectPage(page, null);
    }

    public boolean deductStock(String productId, int quantity) {
        return productMapper.deductStock(productId, quantity) > 0;
    }

    void evictListCaches() {
        redisTemplate.delete(ALL_PRODUCTS_KEY);
        redisTemplate.delete(HOT_PRODUCTS_KEY);
    }
}
