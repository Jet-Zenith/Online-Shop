package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.model.OrderItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    @Insert("""
            <script>
            INSERT INTO order_item
                (id, order_id, product_id, product_name, unit_price, quantity, subtotal)
            VALUES
            <foreach collection='items' item='item' separator=','>
                (#{item.id}, #{item.orderId}, #{item.productId}, #{item.productName},
                 #{item.unitPrice}, #{item.quantity}, #{item.subtotal})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<OrderItem> items);
}
