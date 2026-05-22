package com.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.dto.OrderDTO;
import com.shop.dto.OrderItemDTO;
import com.shop.exception.BusinessException;
import com.shop.mapper.OrderItemMapper;
import com.shop.mapper.OrderMapper;
import com.shop.model.CartItem;
import com.shop.model.OrderItem;
import com.shop.model.OrderStatus;
import com.shop.model.Product;
import com.shop.model.ShopOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderOutboxService orderOutboxService;

    public OrderService(OrderMapper orderMapper, OrderItemMapper orderItemMapper, OrderOutboxService orderOutboxService) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.orderOutboxService = orderOutboxService;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDTO createOrder(String userId, List<CartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            throw BusinessException.badRequest("Cart is empty, cannot create order");
        }

        // 1. 将购物车明细转换为订单明细快照，价格、商品名在此刻固化，避免后续商品改价影响历史订单。
        List<OrderItem> orderItems = cartItems.stream()
                .map(this::toOrderItem)
                .toList();

        // 2. 基于快照汇总订单金额与数量，确保主订单和明细使用同一份计算来源。
        BigDecimal totalAmount = orderItems.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalQuantity = orderItems.stream()
                .mapToInt(OrderItem::getQuantity)
                .sum();

        ShopOrder order = ShopOrder.builder()
                .orderNo(generateOrderNo())
                .userId(userId)
                .totalAmount(totalAmount)
                .totalQuantity(totalQuantity)
                .status(OrderStatus.CREATED.name())
                .build();
        orderMapper.insert(order);

        // 3. 批量写入订单明细，避免按商品数量逐条 insert 形成写入型 N+1 数据库黑洞。
        orderItems.forEach(item -> {
            item.setId(IdWorker.getIdStr());
            item.setOrderId(order.getId());
        });
        int insertedItems = orderItemMapper.insertBatch(orderItems);
        if (insertedItems != orderItems.size()) {
            throw new BusinessException(500, "Failed to persist all order items");
        }

        // 4. 在同一事务内写入 Transactional Outbox，保证“订单落库”和“事件待投递”原子一致。
        OrderDTO orderDTO = toDTO(order, orderItems);
        orderOutboxService.saveOrderCreatedEvent(orderDTO);
        return orderDTO;
    }

    /**
     * 获取当前登录用户的订单列表（分页查询）- 已彻底解决 N+1 性能陷阱
     *
     * @param userId   当前登录用户ID
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 组装好完整明细的分页订单 DTO
     */
    public Page<OrderDTO> getUserOrders(String userId, int pageNum, int pageSize) {
        // 1. 查询当前页的主订单列表
        LambdaQueryWrapper<ShopOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ShopOrder::getUserId, userId)
                .orderByDesc(ShopOrder::getCreateTime);
        Page<ShopOrder> page = orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Page<OrderDTO> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());

        // 2. 提取当前页所有主订单的 ID 集合
        List<String> orderIds = page.getRecords().stream()
                .map(ShopOrder::getId)
                .toList();

        // 3. 用 IN 语句批量查询明细，并在内存中按 orderId 分组
        Map<String, List<OrderItem>> itemsByOrderId = getOrderItemsByOrderIds(orderIds);

        // 4. 将主订单与明细在内存中进行聚合拼装，转换为 DTO 返回
        dtoPage.setRecords(page.getRecords().stream()
                // 使用 getOrDefault 防止某些异常订单没有明细导致 NullPointerException
                .map(order -> toDTO(order, itemsByOrderId.getOrDefault(order.getId(), Collections.emptyList())))
                .toList());

        return dtoPage;
    }

    /**
     * 获取单个订单详情
     * * @param userId  当前登录用户ID（用于越权校验）
     * @param orderId 订单主键ID
     * @return 包含明细的订单 DTO
     */
    public OrderDTO getUserOrder(String userId, String orderId) {
        // 1. 根据主键快速查询订单主表
        ShopOrder order = orderMapper.selectById(orderId);

        // 2. 核心安全防御：水平越权 校验
        // 必须校验查询出的订单归属人是否为当前登录用户。
        // 如果订单不存在，或订单归属人与当前用户不符，统一抛出 404 Not Found。
        if (order == null || !userId.equals(order.getUserId())) {
            throw BusinessException.notFound("Order not found");
        }

        // 3. 查询明细并组装返回
        return toDTO(order, getOrderItems(orderId));
    }

    private List<OrderItem> getOrderItems(String orderId) {
        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderItem::getOrderId, orderId);
        return orderItemMapper.selectList(wrapper);
    }

    /**
     * 内部工具方法：根据订单 ID 列表批量查询并分组商品明细
     */
    private Map<String, List<OrderItem>> getOrderItemsByOrderIds(List<String> orderIds) {
        // 防御性拦截：如果主订单列表为空，直接返回空 Map，防止生成 WHERE order_id IN () 导致 SQL 语法异常
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyMap();
        }

        LambdaQueryWrapper<OrderItem> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OrderItem::getOrderId, orderIds);

        // 执行一次单表全量匹配查询，利用 Java 8 Stream 在内存中按 orderId 归类汇总
        return orderItemMapper.selectList(wrapper).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
    }

    private OrderItem toOrderItem(CartItem cartItem) {
        Product product = cartItem.getProduct();
        if (product == null || product.getPrice() == null || cartItem.getQuantity() <= 0) {
            throw BusinessException.badRequest("Invalid cart item");
        }
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));
        return OrderItem.builder()
                .productId(product.getId())
                .productName(product.getName())
                .unitPrice(product.getPrice())
                .quantity(cartItem.getQuantity())
                .subtotal(subtotal)
                .build();
    }

    private OrderDTO toDTO(ShopOrder order, List<OrderItem> items) {
        return OrderDTO.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .totalAmount(order.getTotalAmount())
                .totalQuantity(order.getTotalQuantity())
                .status(order.getStatus())
                .createTime(order.getCreateTime())
                .items(items.stream()
                        .map(item -> OrderItemDTO.builder()
                                .productId(item.getProductId())
                                .productName(item.getProductName())
                                .unitPrice(item.getUnitPrice())
                                .quantity(item.getQuantity())
                                .subtotal(item.getSubtotal())
                                .build())
                        .toList())
                .build();
    }

    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_TIME_FORMAT)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
