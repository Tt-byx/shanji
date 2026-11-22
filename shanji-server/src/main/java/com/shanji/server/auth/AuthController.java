package com.shanji.server.auth;

import com.shanji.server.common.ApiResponse;
import com.shanji.server.common.BizException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.AuthRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new BizException(HttpStatus.CONFLICT, "用户名已存在");
        }
        long now = System.currentTimeMillis();
        User user = User.builder()
            .username(req.getUsername())
            .passwordHash(passwordEncoder.encode(req.getPassword()))
            .createdAt(now)
            .updatedAt(now)
            .build();
        userRepository.save(user);
        return ApiResponse.ok(token(user));
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.AuthRequest req) {
        User user = userRepository.findByUsername(req.getUsername())
            .orElseThrow(() -> new BizException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new BizException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return ApiResponse.ok(token(user));
    }

    private AuthDtos.AuthResponse token(User user) {
        return new AuthDtos.AuthResponse(
            jwtService.issue(user.getUsername()),
            jwtService.expireAt(),
            user.getUsername()
        );
    }
}
