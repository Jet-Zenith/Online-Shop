package com.shop.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("event_outbox")
/**
 * 本地消息表实体，用于 Transactional Outbox 模式下可靠保存待投递事件。
 */
public class EventOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;// Outbox 事件主键 ID
    private String aggregateType;// 聚合类型，例如 ORDER，表示这条事件属于哪个业务聚合
    private String aggregateId;// 聚合 ID，例如订单 ID，用于定位事件对应的业务对象
    private String eventType;// 事件类型，例如 ORDER_CREATED
    private String payload;// 事件正文 JSON，保存真正要投递给消息队列的业务数据
    private String status;// 投递状态，对应 EventOutboxStatus 枚举值
    private Integer retryCount;// 已重试投递次数
    private String lastError;// 最近一次投递失败的错误信息

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;// 创建时间，由 MyBatis-Plus 自动填充

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;// 最近更新时间，由 MyBatis-Plus 自动填充
}
