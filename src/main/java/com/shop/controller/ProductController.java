package com.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.Result;
import com.shop.dto.ProductRequest;
import com.shop.exception.BusinessException;
import com.shop.model.Product;
import com.shop.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * 创建新商品
     * @param productRequest 包含商品基础信息的请求体，由 @Valid 进行参数合法性校验
     */
    @PostMapping
    public Result<Product> createProduct(@Valid @RequestBody ProductRequest productRequest) {
        return Result.success(productService.createProduct(toProduct(productRequest)));
    }

    /**
     * 分页获取商品列表（C端商品列表、分类页面的核心入口）
     * 通过限制单次查询的条数，建立内存防护墙，彻底根除因全表扫描引发的 OOM（内存溢出）隐患。
     *
     * @param pageNum  当前查询的页码。若前端未传该参数，则自动激活保底机制，
     * 通过 "" + 拼接将全局 int 常量转换为注解所需的 String 类型，默认作为第 1 页。
     * @param pageSize 每页展示的数据条数。若前端未传，则采用系统默认全局配置（如默认每页 10 条）。
     * @return 统一响应体，内部包裹 MyBatis-Plus 的分页封装对象 Page，包含当前页数据、总条数、总页数等控制信息
     */
    @GetMapping
    public Result<Page<Product>> getProducts(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_NUM) int pageNum,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {
        // 显式将分页参数透传给 Service 层，由 MyBatis-Plus 分页拦截器自动在 SQL 末尾拼接 LIMIT/OFFSET 语句
        return Result.success(productService.getProductsByPage(pageNum, pageSize));
    }

    /**
     * 获取热门商品列表（通常用于首页展示）
     * 性能优化：底层一般直连 Redis 缓存，由定时任务提前预热数据。
     */
    @GetMapping("/hot")
    public Result<List<Product>> getHotProducts() {
        return Result.success(productService.getHotProducts());
    }

    /**
     * 商品高级搜索
     * @param keyword  搜索关键字
     * @param category 商品分类
     */
    @GetMapping("/search")
    public Result<List<Product>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return Result.success(productService.searchProducts(keyword, category));
    }

    /**
     * 重建商品搜索索引
     * 运维接口：当 MySQL 数据与搜索引擎（如 ES/Redis）数据不一致时，手动触发全量同步。
     */
    @PostMapping("/search/rebuild")
    public Result<Map<String, Object>> rebuildSearchIndex() {
        int indexed = productService.rebuildSearchIndex();
        return Result.success(Map.of("indexed", indexed));
    }

    /**
     * 分页查询商品（C端展示的主力接口）
     */
    @GetMapping("/page")
    public Result<Page<Product>> getProductsPage(
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_NUM) int pageNum,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int pageSize) {
        return Result.success(productService.getProductsByPage(pageNum, pageSize));
    }

    /**
     * 根据 ID 获取单个商品详情
     */
    @GetMapping("/{id}")
    public Result<Product> getProduct(@PathVariable String id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            throw BusinessException.notFound("Product not found");
        }
        return Result.success(product);
    }

    /**
     * 更新商品信息
     */
    @PutMapping("/{id}")
    public Result<Product> updateProduct(@PathVariable String id, @Valid @RequestBody ProductRequest productRequest) {
        return Result.success(productService.updateProduct(id, toProduct(productRequest)));
    }

    /**
     * 下架/删除商品
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteProduct(@PathVariable String id) {
        if (!productService.deleteProduct(id)) {
            throw BusinessException.notFound("Product not found");
        }
        return Result.success();
    }

    /**
     * 数据转换辅助方法：将前端请求对象 DTO 转换为数据库实体 Entity
     * 避免使用 BeanUtils 反射拷贝，提高性能且防止恶意参数注入。
     */
    private Product toProduct(ProductRequest productRequest) {
        Product product = new Product();
        product.setName(productRequest.getName());
        product.setDescription(productRequest.getDescription());
        product.setPrice(productRequest.getPrice());
        product.setStock(productRequest.getStock());
        product.setCategory(productRequest.getCategory());
        return product;
    }
}
