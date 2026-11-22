package com.shanji.server.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDtos {

    /** 注册/登录请求。channel 预留：account=账号密码，sms=手机号验证码（后续实现） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthRequest {
        @NotBlank
        @Size(min = 3, max = 64)
        private String username;

        @NotBlank
        @Size(min = 6, max = 64)
        private String password;

        private String channel = "account";
        private String code;   // 预留验证码字段
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private long expiresAt;
        private String username;
    }
}
