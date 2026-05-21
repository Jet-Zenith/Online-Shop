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

    // 使用构造器注入，Spring Boot 会自动装配，比 @Autowired 字段注入更安全
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 获取当前登录用户的订单列表（分页查询）
     *
     * @param user     当前已登录用户（由全局 ArgumentResolver 自动拦截并注入）
     * @param pageNum  当前页码，默认为 1
     * @param pageSize 每页条数，默认为 10
     * @return 统一响应体，内部包裹 MyBatis-Plus 的分页对象 Page
     */
    @GetMapping
    public Result<Page<OrderDTO>> getMyOrders(
            @CurrentUser User user,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        // 将干净的参数传给 Service 层处理
        return Result.success(orderService.getUserOrders(user.getId(), pageNum, pageSize));
    }

    /**
     * 获取单个订单详情
     *
     * @param user 当前已登录用户
     * @param id   URL 路径中提取的订单 ID
     * @return 订单详细信息 DTO
     */
    @GetMapping("/{id}")
    public Result<OrderDTO> getMyOrder(@CurrentUser User user, @PathVariable String id) {
        // 【安全核心】必须同时传入当前用户的 ID 进行联合查询！
        // 绝对禁止仅凭 orderId 查询，以防止“水平越权（越权访问他人订单）”的安全漏洞。
        return Result.success(orderService.getUserOrder(user.getId(), id));
    }
}
