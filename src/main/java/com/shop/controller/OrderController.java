package com.shop.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.CurrentUser;
import com.shop.common.Result;
import com.shop.dto.OrderDTO;
import com.shop.model.User;
import com.shop.service.OrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Result<Page<OrderDTO>> getMyOrders(
            @CurrentUser User user,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(orderService.getUserOrders(user.getId(), pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public Result<OrderDTO> getMyOrder(@CurrentUser User user, @PathVariable String id) {
        return Result.success(orderService.getUserOrder(user.getId(), id));
    }
}
