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

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public Result<Product> createProduct(@Valid @RequestBody ProductRequest productRequest) {
        return Result.success(productService.createProduct(toProduct(productRequest)));
    }

    @GetMapping("/{id}")
    public Result<Product> getProduct(@PathVariable String id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            throw BusinessException.notFound("Product not found");
        }
        return Result.success(product);
    }

    @GetMapping
    public Result<List<Product>> getAllProducts() {
        return Result.success(productService.getAllProducts());
    }

    @GetMapping("/hot")
    public Result<List<Product>> getHotProducts() {
        return Result.success(productService.getHotProducts());
    }

    @GetMapping("/search")
    public Result<List<Product>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        return Result.success(productService.searchProducts(keyword, category));
    }

    @PutMapping("/{id}")
    public Result<Product> updateProduct(@PathVariable String id, @Valid @RequestBody ProductRequest productRequest) {
        return Result.success(productService.updateProduct(id, toProduct(productRequest)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteProduct(@PathVariable String id) {
        if (!productService.deleteProduct(id)) {
            throw BusinessException.notFound("Product not found");
        }
        return Result.success();
    }

    @GetMapping("/page")
    public Result<Page<Product>> getProductsPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(productService.getProductsByPage(pageNum, pageSize));
    }

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
