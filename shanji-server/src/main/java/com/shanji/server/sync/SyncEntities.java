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

    /** 固定支出（F8：订阅/周期规则进云同步；与客户端 recurring 表字段一一对应） */
    @Entity(name = "FixedRecord")
    @Table(name = "user_fixed")
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FixedRecord {
        @Id
        @Column(length = 36)
        private String id;

        @Column(name = "user_id", nullable = false)
        private Long userId;

        private String type;
        private double amount;
        private String merchant = "";
        private String category = "";

        @Column(name = "account_id")
        private String accountId = "acc_cash";

        @Column(name = "day_of_month")
        private int dayOfMonth;

        private int enabled;

        @Column(name = "last_run_date")
        private String lastRunDate = "";

        private String cycle = "monthly";
        private int dueMonth;
        @Column(name = "auto_record")
        private int autoRecord = 1;
        private String source = "manual";

        @Column(name = "updated_at")
        private long updatedAt;

        private int deleted;
    }
}
