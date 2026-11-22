package com.shanji.server.sync;

import com.shanji.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @GetMapping("/pull")
    public ApiResponse<SyncDtos.PullResponse> pull(
        Authentication auth,
        @RequestParam(defaultValue = "0") long since
    ) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(syncService.pull(userId, since));
    }

    @PostMapping("/push")
    public ApiResponse<SyncDtos.PushResponse> push(
        Authentication auth,
        @RequestBody SyncDtos.PushRequest req
    ) {
        Long userId = (Long) auth.getPrincipal();
        return ApiResponse.ok(syncService.push(userId, req));
    }
}
