package com.shop.model;

/**
 * Outbox 事件投递状态枚举。
 */
public enum EventOutboxStatus {
    PENDING,// 待投递，后台 relay 会扫描这个状态
    SENT,// 已成功投递到消息通道
    FAILED// 多次重试后仍投递失败，需要人工或补偿任务介入。
}
