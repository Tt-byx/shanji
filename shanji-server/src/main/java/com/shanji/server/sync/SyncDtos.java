package com.shanji.server.sync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class SyncDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushRequest {
        private List<SyncEntities.TxRecord> txs;
        private List<SyncEntities.AccountRecord> accounts;
        private List<SyncEntities.BudgetRecord> budgets;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PushResponse {
        private long serverTime;
        private int accepted;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PullResponse {
        private long serverTime;
        private List<SyncEntities.TxRecord> txs;
        private List<SyncEntities.AccountRecord> accounts;
        private List<SyncEntities.BudgetRecord> budgets;
    }
}
