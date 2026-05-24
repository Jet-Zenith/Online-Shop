package com.shop.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "shop_products")
public class ProductSearchDocument {

    @Id
    private String id; // ES 文档 ID，和商品表 product.id 保持一致

    @Field(type = FieldType.Text, analyzer = "standard")
    private String name; // 商品名称，用于全文检索

    @Field(type = FieldType.Text, analyzer = "standard")
    private String description; // 商品描述，用于全文检索

    @Field(type = FieldType.Double)
    private BigDecimal price; // 商品价格，用于展示、筛选或排序

    @Field(type = FieldType.Integer)
    private Integer stock; // 商品库存，用于展示或过滤无库存商品

    @Field(type = FieldType.Keyword)
    private String category; // 商品分类，Keyword 类型适合精确过滤
}
