package com.shanji.server.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 云同步：push（客户端脏数据 upsert，updated_at LWW）→ pull（取 since 之后的全部行，含软删）。
 * 单用户多端场景足够，不做冲突 UI。
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final TxRepo txRepo;
    private final AccountRepo accountRepo;
    private final BudgetRepo budgetRepo;

    @Transactional
    public SyncDtos.PushResponse push(Long userId, SyncDtos.PushRequest req) {
        int accepted = 0;
        if (req.getTxs() != null) {
            for (var incoming : req.getTxs()) {
                if (merge(txRepo.findById(incoming.getId()).orElse(null), incoming.getUpdatedAt())) continue;
                incoming.setUserId(userId);
                txRepo.save(incoming);
                accepted++;
            }
        }
        if (req.getAccounts() != null) {
            for (var incoming : req.getAccounts()) {
                if (merge(accountRepo.findById(incoming.getId()).orElse(null), incoming.getUpdatedAt())) continue;
                incoming.setUserId(userId);
                accountRepo.save(incoming);
                accepted++;
            }
        }
        if (req.getBudgets() != null) {
            for (var incoming : req.getBudgets()) {
                if (merge(budgetRepo.findById(incoming.getId()).orElse(null), incoming.getUpdatedAt())) continue;
                incoming.setUserId(userId);
                budgetRepo.save(incoming);
                accepted++;
            }
        }
        return new SyncDtos.PushResponse(System.currentTimeMillis(), accepted);
    }

    /** LWW：已有行且不比客户端旧 → 忽略；否则接受（返回 true 表示忽略） */
    private boolean merge(Object existing, long incomingUpdatedAt) {
        if (existing == null) return false;
        long existingUpdated = extractUpdatedAt(existing);
        return existingUpdated >= incomingUpdatedAt;
    }

    private long extractUpdatedAt(Object record) {
        if (record instanceof SyncEntities.TxRecord r) return r.getUpdatedAt();
        if (record instanceof SyncEntities.AccountRecord r) return r.getUpdatedAt();
        if (record instanceof SyncEntities.BudgetRecord r) return r.getUpdatedAt();
        return 0;
    }

    @Transactional(readOnly = true)
    public SyncDtos.PullResponse pull(Long userId, long since) {
        return new SyncDtos.PullResponse(
            System.currentTimeMillis(),
            txRepo.findSince(userId, since),
            accountRepo.findSince(userId, since),
            budgetRepo.findSince(userId, since)
        );
    }
}
