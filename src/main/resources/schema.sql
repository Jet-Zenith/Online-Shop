CREATE TABLE IF NOT EXISTS `user` (
    `id`          VARCHAR(64)  NOT NULL COMMENT 'User ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT 'Username',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT 'Email',
    `password`    VARCHAR(255) NOT NULL COMMENT 'BCrypt password hash',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `create_user` VARCHAR(64)  DEFAULT NULL COMMENT 'Created by',
    `update_user` VARCHAR(64)  DEFAULT NULL COMMENT 'Updated by',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Users';

CREATE TABLE IF NOT EXISTS `product` (
    `id`          VARCHAR(64)   NOT NULL COMMENT 'Product ID',
    `name`        VARCHAR(100)  NOT NULL COMMENT 'Product name',
    `description` VARCHAR(500)  DEFAULT NULL COMMENT 'Product description',
    `price`       DECIMAL(10,2) NOT NULL COMMENT 'Product price',
    `stock`       INT           NOT NULL DEFAULT 0 COMMENT 'Available stock',
    `category`    VARCHAR(50)   DEFAULT NULL COMMENT 'Product category',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `create_user` VARCHAR(64)   DEFAULT NULL COMMENT 'Created by',
    `update_user` VARCHAR(64)   DEFAULT NULL COMMENT 'Updated by',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Products';

CREATE TABLE IF NOT EXISTS `shop_order` (
    `id`             VARCHAR(64)    NOT NULL COMMENT 'Order ID',
    `order_no`       VARCHAR(64)    NOT NULL COMMENT 'Business order number',
    `user_id`        VARCHAR(64)    NOT NULL COMMENT 'User ID',
    `total_amount`   DECIMAL(12,2)  NOT NULL COMMENT 'Total amount',
    `total_quantity` INT            NOT NULL COMMENT 'Total quantity',
    `status`         VARCHAR(32)    NOT NULL COMMENT 'Order status',
    `create_time`    DATETIME       DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `update_time`    DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    `create_user`    VARCHAR(64)    DEFAULT NULL COMMENT 'Created by',
    `update_user`    VARCHAR(64)    DEFAULT NULL COMMENT 'Updated by',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    INDEX `idx_order_user_time` (`user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Orders';

CREATE TABLE IF NOT EXISTS `order_item` (
    `id`           VARCHAR(64)   NOT NULL COMMENT 'Order item ID',
    `order_id`     VARCHAR(64)   NOT NULL COMMENT 'Order ID',
    `product_id`   VARCHAR(64)   NOT NULL COMMENT 'Product ID',
    `product_name` VARCHAR(100)  NOT NULL COMMENT 'Product name snapshot',
    `unit_price`   DECIMAL(10,2) NOT NULL COMMENT 'Unit price snapshot',
    `quantity`     INT           NOT NULL COMMENT 'Quantity',
    `subtotal`     DECIMAL(12,2) NOT NULL COMMENT 'Subtotal',
    PRIMARY KEY (`id`),
    INDEX `idx_order_item_order` (`order_id`),
    INDEX `idx_order_item_product` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Order items';

CREATE TABLE IF NOT EXISTS `event_outbox` (
    `id`             VARCHAR(64)   NOT NULL COMMENT 'Outbox event ID',
    `aggregate_type` VARCHAR(64)   NOT NULL COMMENT 'Aggregate type',
    `aggregate_id`   VARCHAR(64)   NOT NULL COMMENT 'Aggregate ID',
    `event_type`     VARCHAR(64)   NOT NULL COMMENT 'Event type',
    `payload`        JSON          NOT NULL COMMENT 'Event payload',
    `status`         VARCHAR(32)   NOT NULL COMMENT 'PENDING/SENT/FAILED',
    `retry_count`    INT           NOT NULL DEFAULT 0 COMMENT 'Retry count',
    `last_error`     VARCHAR(500)  DEFAULT NULL COMMENT 'Last publish error',
    `create_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    `update_time`    DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (`id`),
    INDEX `idx_outbox_status_time` (`status`, `create_time`),
    INDEX `idx_outbox_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transactional outbox events';
