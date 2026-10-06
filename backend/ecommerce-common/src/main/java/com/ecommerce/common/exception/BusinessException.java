package com.ecommerce.common.exception;

import com.ecommerce.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 * <p>Service 层校验不通过时抛出，由全局异常处理器统一转成 {@code Result}，
 * 避免在每个 Controller 里写 try-catch。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务状态码 */
    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.FAIL.getCode();
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    /** 业务异常不需要堆栈，避免性能损耗 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}