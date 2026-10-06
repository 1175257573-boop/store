package com.ecommerce.common.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结果包装。
 * <p>Controller 一律返回该结构，前端只需判断 {@code code == 200}。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    /** 业务状态码，200 表示成功 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据，失败时为 null */
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功且无返回数据 */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功且携带数据 */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 成功且自定义提示语 */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /** 失败，使用默认业务状态码 */
    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCode.FAIL.getCode(), message, null);
    }

    /** 失败，指定业务状态码 */
    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败，使用枚举中定义的状态码与提示语 */
    public static <T> Result<T> error(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }
}