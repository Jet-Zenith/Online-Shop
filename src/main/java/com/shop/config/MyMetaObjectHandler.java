package com.shop.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.shop.common.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 公共字段自动填充处理器
 */
@Component
@Slf4j
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入操作时的填充逻辑
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        log.info("开始插入填充公共字段...");

        // 1. 填充时间
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());

        // 2. 填充用户 ID (从 ThreadLocal 中获取)
        String currentId = BaseContext.getCurrentId();
        if (currentId == null) currentId = "system"; // 防止定时任务等无用户上下文的操作报错

        this.strictInsertFill(metaObject, "createUser", String.class, currentId);
        this.strictInsertFill(metaObject, "updateUser", String.class, currentId);
    }

    /**
     * 更新操作时的填充逻辑
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        log.info("开始更新填充公共字段...");

        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());

        String currentId = BaseContext.getCurrentId();
        if (currentId == null) currentId = "system";

        this.strictUpdateFill(metaObject, "updateUser", String.class, currentId);
    }
}