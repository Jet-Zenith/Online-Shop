package com.shop.controller;

import com.shop.common.Result;
import com.shop.dto.CartRequest;
import com.shop.model.Cart;
import com.shop.model.User;
import com.shop.service.CartService;
import com.shop.service.UserService;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;
    private final UserService userService;

    public CartController(CartService cartService, UserService userService) {
        this.cartService = cartService;
        this.userService = userService;
    }

    @GetMapping
    public Result<Cart> getCart(@RequestHeader("X-Session-ID") String sessionId) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        Cart cart = cartService.getCart(user.getId());
        return Result.success(cart);
    }

    @PostMapping("/items")
    public Result<Cart> addItemToCart(
            @RequestHeader("X-Session-ID") String sessionId,
            @Valid @RequestBody CartRequest cartRequest) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        Cart cart = cartService.addItemToCart(user.getId(), cartRequest.getProductId(), cartRequest.getQuantity());
        return Result.success(cart);
    }

    @DeleteMapping("/items/{productId}")
    public Result<Cart> removeItemFromCart(
            @RequestHeader("X-Session-ID") String sessionId,
            @PathVariable String productId) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        Cart cart = cartService.removeItemFromCart(user.getId(), productId);
        return Result.success(cart);
    }

    @PutMapping("/items/{productId}")
    public Result<Cart> updateItemQuantity(
            @RequestHeader("X-Session-ID") String sessionId,
            @PathVariable String productId,
            @RequestParam int quantity) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        Cart cart = cartService.updateItemQuantity(user.getId(), productId, quantity);
        return Result.success(cart);
    }

    @DeleteMapping
    public Result<Cart> clearCart(@RequestHeader("X-Session-ID") String sessionId) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        Cart cart = cartService.clearCart(user.getId());
        return Result.success(cart);
    }

    @PostMapping("/merge")
    public Result<Cart> mergeCart(
            @RequestHeader("X-Session-ID") String sessionId,
            @RequestBody Cart temporaryCart) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        boolean merged = cartService.mergeCart(user.getId(), temporaryCart);
        if (merged) {
            Cart cart = cartService.getCart(user.getId());
            return Result.success(cart);
        }
        return Result.error(500, "合并购物车失败，请稍后重试");
    }

    @PostMapping("/checkout")
    public Result<String> checkout(@RequestHeader("X-Session-ID") String sessionId) {
        if (!userService.validateSession(sessionId)) {
            return Result.error(401, "会话已过期或无效，请重新登录");
        }

        User user = userService.getUserBySession(sessionId);
        try {
            boolean success = cartService.checkout(user.getId());
            if (success) {
                return Result.success("结算成功");
            }
            return Result.error(500, "系统繁忙，结算失败");
        } catch (RuntimeException e) {
            return Result.error(400, e.getMessage());
        }
    }
}
