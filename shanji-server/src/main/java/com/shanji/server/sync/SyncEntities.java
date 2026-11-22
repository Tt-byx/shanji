package com.shanji.server.sync;

import jakarta.persistence.*;
import lombok.*;

public class SyncEntities {

    @Entity(name = "TxRecord")
    @Table(name = "user_tx")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TxRecord {
        @Id
        @Column(length = 36)
        private String id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        private String type;
        private double amount;
        private String merchant = "";
        private String category = "";
        private String note = "";
        private long ts;
        private String source = "manual";

        @Column(name = "account_id")
        private String accountId = "acc_cash";

        @Column(name = "to_account_id")
        private String toAccountId = "";

        @Column(name = "updated_at")
        private long updatedAt;

        private int deleted;
    }

    @Entity(name = "AccountRecord")
    @Table(name = "user_account")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AccountRecord {
        @Id
        @Column(length = 36)
        private String id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        private String name;
        private String type;
        @Column(name = "initial_balance")
        private double initialBalance;

        @Column(name = "is_default")
        private int isDefault;

        @Column(name = "sort_order")
        private int sortOrder;

        @Column(name = "updated_at")
        private long updatedAt;

        private int deleted;
    }

    @Entity(name = "BudgetRecord")
    @Table(name = "user_budget")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class BudgetRecord {
        @Id
        @Column(length = 36)
        private String id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        private String scope;
        private String category;
        private double amount;

        @Column(name = "updated_at")
        private long updatedAt;

        private int deleted;
    }
}
