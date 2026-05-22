package com.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.dto.StockDeductionRequest;
import com.shop.model.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    @Update("UPDATE product SET stock = stock - #{quantity} WHERE id = #{productId} AND stock >= #{quantity}")
    int deductStock(@Param("productId") String productId, @Param("quantity") int quantity);

    @Update("""
            <script>
            UPDATE product
            SET stock = CASE id
            <foreach collection='items' item='item'>
                WHEN #{item.productId} THEN stock - #{item.quantity}
            </foreach>
            END
            WHERE
            <foreach collection='items' item='item' open='(' separator=' OR ' close=')'>
                (id = #{item.productId} AND stock >= #{item.quantity})
            </foreach>
            </script>
            """)
    int batchDeductStock(@Param("items") List<StockDeductionRequest> items);
}
