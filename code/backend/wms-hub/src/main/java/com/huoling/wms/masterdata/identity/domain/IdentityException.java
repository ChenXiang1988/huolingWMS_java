package com.huoling.wms.masterdata.identity.domain;

/**
 * 身份与权限领域的业务异常。错误码与接口契约的错误码表一一对应。
 */
public class IdentityException extends RuntimeException {

    private final String code;

    public IdentityException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
