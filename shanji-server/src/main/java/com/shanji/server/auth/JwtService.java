package com.shanji.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    @Value("${shanji.jwt.secret}")
    private String secret;

    @Value("${shanji.jwt.expire-days}")
    private long expireDays;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 签发 token（预留 channel：后续手机号验证码登录复用） */
    public String issue(String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
            .subject(username)
            .issuedAt(new Date(now))
            .expiration(new Date(now + expireDays * 24 * 3600_000L))
            .signWith(key())
            .compact();
    }

    /** 校验并解析用户名，非法/过期返回 null */
    public String parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key()).build()
                .parseSignedClaims(token).getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public long expireAt() {
        return System.currentTimeMillis() + expireDays * 24 * 3600_000L;
    }
}
