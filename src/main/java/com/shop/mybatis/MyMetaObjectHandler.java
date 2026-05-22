package com.shop.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.shop.common.BaseContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String currentId = currentOperator();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "createUser", String.class, currentId);
        this.strictInsertFill(metaObject, "updateUser", String.class, currentId);
        log.debug("Filled insert audit fields for operator {}", currentId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        String currentId = currentOperator();
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updateUser", String.class, currentId);
        log.debug("Filled update audit fields for operator {}", currentId);
    }

    private String currentOperator() {
        String currentId = BaseContext.getCurrentId();
        return currentId == null ? "system" : currentId;
    }
}
