package com.roydon.dear.common;

import lombok.Data;

import java.io.Serializable;

@Data
public class BaseResult<T> implements Serializable {

    private T data;
    private String message;
    private int code = CODE_SUCCESS;

    public BaseResult() {
    }

    public BaseResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> BaseResult<T> newSuccess() {
        return new BaseResult<>(CODE_SUCCESS, "success", null);
    }

    public static <T> BaseResult<T> newSuccess(T data) {
        return new BaseResult<>(CODE_SUCCESS, "", data);
    }

//    public static <T> BaseResult<T> newSuccess(String message) {
//        return new BaseResult<>(CODE_SUCCESS, message, null);
//    }

    public static <T> BaseResult<T> newSuccess(T data, String message) {
        return new BaseResult<>(CODE_SUCCESS, message, data);
    }

    public BaseResult<T> ok(Object data, String msg) {
        this.code = CODE_SUCCESS;
        this.message = msg;
        this.data = (T) data;
        return this;
    }

    public static <T> BaseResult<T> newError(String message) {
        return new BaseResult<>(CODE_SERVER_ERROR, message, null);
    }

    public static <T> BaseResult<T> newError() {
        return new BaseResult<>(CODE_SERVER_ERROR, "未知", null);
    }

    public static <T> BaseResult<T> newException(String message) {
        return new BaseResult<>(CODE_SUCCESS_ERR, message, null);
    }

    public BaseResult<T> error(int code, String msg, Object data) {
        this.code = code;
        this.message = msg;
        this.data = (T) data;
        return this;
    }

    public BaseResult<T> error(String msg, Object data) {
        this.message = msg;
        this.data = (T) data;
        return this;
    }

    public BaseResult<T> error(String msg) {
        this.code = CODE_SERVER_ERROR;
        this.message = msg;
        return this;
    }

    public static <T> BaseResult<T> newAuthError() {
        return new BaseResult<>(CODE_NO_LOGIN, "未认证", null);
    }

    public static BaseResult forbidden(String message) {
        return fail(CODE_FORBIDDEN, message);
    }

    public static BaseResult unauthorized(String message) {
        return fail(CODE_NO_LOGIN, message);
    }

    public static BaseResult fail(int code, String message) {
        return new BaseResult<>(code, message, null);
    }

    public static final Integer CODE_SUCCESS = 200;
    public static final Integer CODE_SUCCESS_TIP = 220;
    public static final Integer CODE_SUCCESS_WARN = 230;
    public static final Integer CODE_SUCCESS_ERR = 240;

    public static final Integer CODE_NO_LOGIN = 401;
    public static final Integer CODE_FORBIDDEN = 403;
    public static final Integer CODE_NOT_ALLOWED = 405;
    public static final Integer CODE_NO_REG_SYS = 420;
    public static final Integer CODE_NO_REG_MODULE = 421;

    public static final Integer CODE_SERVER_ERROR = 500;
}
