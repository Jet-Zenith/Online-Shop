package com.shop.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    private Integer code;    // 业务状态码：如 200 成功，500 失败，401 无权限
    private String message;  // 提示信息
    private T data;          // 核心数据负载
    private Long timestamp;  // 时间戳

    private Result() {
        this.timestamp = System.currentTimeMillis();
    }

    // 成功时的快捷调用
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    // 失败时的快捷调用
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> error(String message) {
        return error(500, message);
    }
}