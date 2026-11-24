-- 固定支出（F8）：订阅 + 周期规则进云同步；字段与客户端 recurring 表一一对应
CREATE TABLE user_fixed (
    id           VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    type         VARCHAR(16) NOT NULL,
    amount       DOUBLE      NOT NULL,
    merchant     VARCHAR(60) NOT NULL DEFAULT '',
    category     VARCHAR(32) NOT NULL DEFAULT '',
    account_id   VARCHAR(36) NOT NULL DEFAULT 'acc_cash',
    day_of_month INT         NOT NULL DEFAULT 1,
    enabled      INT         NOT NULL DEFAULT 1,
    last_run_date VARCHAR(16) NOT NULL DEFAULT '',
    cycle        VARCHAR(16) NOT NULL DEFAULT 'monthly',
    due_month    INT         NOT NULL DEFAULT 0,
    auto_record  INT         NOT NULL DEFAULT 1,
    source       VARCHAR(16) NOT NULL DEFAULT 'manual',
    updated_at   BIGINT      NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0,
    INDEX idx_user_fixed_user_updated (user_id, updated_at)
);
