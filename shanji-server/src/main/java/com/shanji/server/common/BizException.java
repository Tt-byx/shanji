package com.shanji.server.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BizException extends RuntimeException {
    private final HttpStatus status;
    private final String msg;

    public BizException(HttpStatus status, String msg) {
        super(msg);
        this.status = status;
        this.msg = msg;
    }
}
