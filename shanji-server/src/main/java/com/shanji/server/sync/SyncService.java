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
    private final FixedRepo fixedRepo;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SyncService.class);

    /**
     * push 入口防御（S3 简版）：一条脏数据（超列宽/空 id）不能毒死整个同步——
     * 按 V1/V2 DDL 列宽截断字符串、blank id 直接跳过，坏行单独计数进日志。
     */
    @Transactional
    public SyncDtos.PushResponse push(Long userId, SyncDtos.PushRequest req) {
        int accepted = 0;
        int skipped = 0;
        if (req.getTxs() != null) {
            for (var incoming : req.getTxs()) {
                if (incoming.getId() == null || incoming.getId().isBlank()) { skipped++; continue; }
                var existing = txRepo.findById(incoming.getId()).orElse(null);
                if (ownedByOther(existing != null ? existing.getUserId() : null, userId)) { skipped++; continue; }
                if (merge(existing, incoming.getUpdatedAt())) continue;
                cutTx(incoming);
                incoming.setUserId(userId);
                txRepo.save(incoming);
                accepted++;
            }
        }
        if (req.getAccounts() != null) {
            for (var incoming : req.getAccounts()) {
                if (incoming.getId() == null || incoming.getId().isBlank()) { skipped++; continue; }
                var existing = accountRepo.findById(incoming.getId()).orElse(null);
                if (ownedByOther(existing != null ? existing.getUserId() : null, userId)) { skipped++; continue; }
                if (merge(existing, incoming.getUpdatedAt())) continue;
                incoming.setName(cut(incoming.getName(), 32));
                incoming.setType(cut(incoming.getType(), 16));
                incoming.setUserId(userId);
                accountRepo.save(incoming);
                accepted++;
            }
        }
        if (req.getBudgets() != null) {
            for (var incoming : req.getBudgets()) {
                if (incoming.getId() == null || incoming.getId().isBlank()) { skipped++; continue; }
                var existing = budgetRepo.findById(incoming.getId()).orElse(null);
                if (ownedByOther(existing != null ? existing.getUserId() : null, userId)) { skipped++; continue; }
                if (merge(existing, incoming.getUpdatedAt())) continue;
                incoming.setScope(cut(incoming.getScope(), 16));
                incoming.setCategory(cut(incoming.getCategory(), 32));
                incoming.setUserId(userId);
                budgetRepo.save(incoming);
                accepted++;
            }
        }
        if (req.getFixeds() != null) {
            for (var incoming : req.getFixeds()) {
                if (incoming.getId() == null || incoming.getId().isBlank()) { skipped++; continue; }
                var existing = fixedRepo.findById(incoming.getId()).orElse(null);
                if (ownedByOther(existing != null ? existing.getUserId() : null, userId)) { skipped++; continue; }
                if (merge(existing, incoming.getUpdatedAt())) continue;
                cutFixed(incoming);
                incoming.setUserId(userId);
                fixedRepo.save(incoming);
                accepted++;
            }
        }
        if (skipped > 0) {
            // 截断/跳过计数：定位「哪类数据有多少行没进」而不暴露内容
            log.warn("push skipped {} rows (blank id or owned by another user), accepted {}", skipped, accepted);
        }
        return new SyncDtos.PushResponse(System.currentTimeMillis(), accepted);
    }

    /** S4：已有行属于别的用户 → 不覆盖不改写（pull 侧早已带 userId 过滤，这里补齐 push 侧对称性） */
    private boolean ownedByOther(Long existingUserId, Long userId) {
        return existingUserId != null && !existingUserId.equals(userId);
    }

    /** 按 DDL 列宽截断（merchant60/category32/note200/type16/source16/account36） */
    private void cutTx(SyncEntities.TxRecord r) {
        r.setType(cut(r.getType(), 16));
        r.setMerchant(cut(r.getMerchant(), 60));
        r.setCategory(cut(r.getCategory(), 32));
        r.setNote(cut(r.getNote(), 200));
        r.setSource(cut(r.getSource(), 16));
        r.setAccountId(cut(r.getAccountId(), 36));
        r.setToAccountId(cut(r.getToAccountId(), 36));
    }

    private void cutFixed(SyncEntities.FixedRecord r) {
        r.setType(cut(r.getType(), 16));
        r.setMerchant(cut(r.getMerchant(), 60));
        r.setCategory(cut(r.getCategory(), 32));
        r.setAccountId(cut(r.getAccountId(), 36));
        r.setLastRunDate(cut(r.getLastRunDate(), 16));
        r.setCycle(cut(r.getCycle(), 16));
        r.setSource(cut(r.getSource(), 16));
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
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
        if (record instanceof SyncEntities.FixedRecord r) return r.getUpdatedAt();
        return 0;
    }

    @Transactional(readOnly = true)
    public SyncDtos.PullResponse pull(Long userId, long since) {
        return new SyncDtos.PullResponse(
            System.currentTimeMillis(),
            txRepo.findSince(userId, since),
            accountRepo.findSince(userId, since),
            budgetRepo.findSince(userId, since),
            fixedRepo.findSince(userId, since)
        );
    }
}
