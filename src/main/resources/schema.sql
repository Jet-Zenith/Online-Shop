CREATE TABLE IF NOT EXISTS `user` (
    `id`          VARCHAR(64)  NOT NULL COMMENT '用户ID',
    `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
    `email`       VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `password`    VARCHAR(255) NOT NULL COMMENT 'BCrypt加密密码',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_user` VARCHAR(64)  DEFAULT NULL COMMENT '创建人',
    `update_user` VARCHAR(64)  DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `product` (
    `id`          VARCHAR(64)   NOT NULL COMMENT '商品ID',
    `name`        VARCHAR(100)  NOT NULL COMMENT '商品名称',
    `description` VARCHAR(500)  DEFAULT NULL COMMENT '商品描述',
    `price`       DECIMAL(10,2) NOT NULL COMMENT '商品价格',
    `stock`       INT           NOT NULL DEFAULT 0 COMMENT '库存数量',
    `category`    VARCHAR(50)   DEFAULT NULL COMMENT '商品分类',
    `create_time` DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `create_user` VARCHAR(64)   DEFAULT NULL COMMENT '创建人',
    `update_user` VARCHAR(64)   DEFAULT NULL COMMENT '更新人',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品表';
