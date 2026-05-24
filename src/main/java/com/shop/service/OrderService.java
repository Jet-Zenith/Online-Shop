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

    /**
     * 核心交易链路：创建订单并落库
     * <p>
     * 架构意图与核心设计：
     * 1. 【数据快照】：将购物车明细转换为订单明细，在此刻彻底固化商品名称和交易价格，
     * 防止后续商家改名或调价影响已成交的历史订单。
     * 2. 【财务精度】：采用 BigDecimal 进行总金额汇总，严格杜绝浮点数计算导致的财务精度丢失。
     * 3. 【性能极化】：彻底摒弃单条循环写入，采用手动预分配 ID (IdWorker) 配合 insertBatch
     * 实现批量 Bulk Insert，根除 N+1 数据库网络瓶颈，极大提升大促期间的并发吞吐量。
     * 4. 【最终一致性】：采用“本地消息表 (Transactional Outbox Pattern)”模式，
     * 将订单数据与“订单已创建”事件在同一个本地 MySQL 事务中强一致性落盘，彻底解决微服务场景下的消息丢失难题。
     *
     * @param userId    当前结算下单的用户 ID
     * @param cartItems 已通过前置校验（如库存扣减成功）的购物车明细快照
     * @return OrderDTO 组装完毕并成功落盘的订单视图对象
     * @throws BusinessException 当购物车为空，或批量写入明细出现静默失败时抛出，触发外层强事务回滚
     */
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
     * 获取当前登录用户的订单列表
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

    /**
     * 将购物车明细转换为订单明细
     * <p>
     * 架构意图与业务价值：
     * 1. 【防御性编程】：严格拦截无效的商品信息和非法购买数量（如零元购、负数刷单）。
     * 2. 【数据快照】：将此时此刻的商品名称 (ProductName) 和单价 (UnitPrice)
     * 物理拷贝并硬入库到订单明细中。彻底切断与动态商品库的关联，防止未来商家改名、调价导致的历史订单凭证被篡改和财务对账失败。
     * 3. 【财务精度】：采用 BigDecimal 进行小计乘法运算，确保财务数据零误差。
     *
     * @param cartItem 购物车中的单条商品记录
     * @return 固化了价格和名称的订单明细实体
     * @throws BusinessException 当商品信息缺失或购买数量非法时抛出
     */
    private OrderItem toOrderItem(CartItem cartItem) {
        Product product = cartItem.getProduct();

        // Fail-Fast：严防脏数据和恶意篡改
        if (product == null || product.getPrice() == null || cartItem.getQuantity() <= 0) {
            throw BusinessException.badRequest("Invalid cart item");
        }

        // 财务级高精度计算：单价 × 数量
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity()));

        // 构建订单明细快照
        return OrderItem.builder()
                .productId(product.getId())
                .productName(product.getName()) // 固化购买时的商品名
                .unitPrice(product.getPrice())  // 固化购买时的真实单价
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
