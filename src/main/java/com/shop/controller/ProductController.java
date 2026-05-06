package com.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.Result;
import com.shop.dto.ProductRequest;
import com.shop.model.Product;
import com.shop.service.ProductService;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public Result<Product> createProduct(@Valid @RequestBody ProductRequest productRequest) {
        Product product = new Product();
        product.setName(productRequest.getName());
        product.setDescription(productRequest.getDescription());
        product.setPrice(productRequest.getPrice());
        product.setStock(productRequest.getStock());
        product.setCategory(productRequest.getCategory());

        Product createdProduct = productService.createProduct(product);
        return Result.success(createdProduct);
    }

    @GetMapping("/{id}")
    public Result<Product> getProduct(@PathVariable String id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            return Result.error(404, "商品不存在");
        }
        return Result.success(product);
    }

    @GetMapping
    public Result<List<Product>> getAllProducts() {
        List<Product> products = productService.getAllProducts();
        return Result.success(products);
    }

    @GetMapping("/hot")
    public Result<List<Product>> getHotProducts() {
        List<Product> hotProducts = productService.getHotProducts();
        return Result.success(hotProducts);
    }

    @GetMapping("/search")
    public Result<List<Product>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        List<Product> products = productService.searchProducts(keyword, category);
        return Result.success(products);
    }

    @PutMapping("/{id}")
    public Result<Product> updateProduct(
            @PathVariable String id,
            @Valid @RequestBody ProductRequest productRequest) {
        Product existingProduct = productService.getProductById(id);
        if (existingProduct == null) {
            return Result.error(404, "商品不存在，无法更新");
        }

        existingProduct.setName(productRequest.getName());
        existingProduct.setDescription(productRequest.getDescription());
        existingProduct.setPrice(productRequest.getPrice());
        existingProduct.setStock(productRequest.getStock());
        existingProduct.setCategory(productRequest.getCategory());

        Product updatedProduct = productService.updateProduct(id, existingProduct);
        return Result.success(updatedProduct);
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProduct(@PathVariable String id) {
        if (productService.getProductById(id) == null) {
            return Result.error(404, "商品不存在，无法删除");
        }

        productService.deleteProduct(id);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<Page<Product>> getProductsPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {

        Page<Product> pageResult = productService.getProductsByPage(pageNum, pageSize);
        return Result.success(pageResult);
    }
}
