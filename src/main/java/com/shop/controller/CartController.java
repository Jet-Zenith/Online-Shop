package com.shop.controller;

import com.shop.common.CurrentUser;
import com.shop.common.Result;
import com.shop.dto.CartRequest;
import com.shop.dto.OrderDTO;
import com.shop.model.Cart;
import com.shop.model.User;
import com.shop.service.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public Result<Cart> getCart(@CurrentUser User user) {
        return Result.success(cartService.getCart(user.getId()));
    }

    @PostMapping("/items")
    public Result<Cart> addItemToCart(@CurrentUser User user, @Valid @RequestBody CartRequest cartRequest) {
        Cart cart = cartService.addItemToCart(user.getId(), cartRequest.getProductId(), cartRequest.getQuantity());
        return Result.success(cart);
    }

    @DeleteMapping("/items/{productId}")
    public Result<Cart> removeItemFromCart(@CurrentUser User user, @PathVariable String productId) {
        return Result.success(cartService.removeItemFromCart(user.getId(), productId));
    }

    @PutMapping("/items/{productId}")
    public Result<Cart> updateItemQuantity(
            @CurrentUser User user,
            @PathVariable String productId,
            @RequestParam @Min(0) @Max(100) int quantity) {
        return Result.success(cartService.updateItemQuantity(user.getId(), productId, quantity));
    }

    @DeleteMapping
    public Result<Cart> clearCart(@CurrentUser User user) {
        return Result.success(cartService.clearCart(user.getId()));
    }

    @PostMapping("/merge")
    public Result<Cart> mergeCart(@CurrentUser User user, @RequestBody Cart temporaryCart) {
        cartService.mergeCart(user.getId(), temporaryCart);
        return Result.success(cartService.getCart(user.getId()));
    }

    @PostMapping("/checkout")
    public Result<OrderDTO> checkout(@CurrentUser User user) {
        return Result.success(cartService.checkout(user.getId()));
    }
}
