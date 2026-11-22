-- 闪记后端初始表结构
CREATE TABLE user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    BIGINT       NOT NULL,
    updated_at    BIGINT       NOT NULL
);

-- 账单：id 为客户端 UUID 主键，push 幂等 upsert；updated_at 为 LWW 依据；deleted 软删
CREATE TABLE user_tx (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    type         VARCHAR(16)  NOT NULL,
    amount       DOUBLE       NOT NULL,
    merchant     VARCHAR(60)  NOT NULL DEFAULT '',
    category     VARCHAR(32)  NOT NULL DEFAULT '',
    note         VARCHAR(200) NOT NULL DEFAULT '',
    ts           BIGINT       NOT NULL,
    source       VARCHAR(16)  NOT NULL DEFAULT 'manual',
    account_id   VARCHAR(36)  NOT NULL DEFAULT 'acc_cash',
    to_account_id VARCHAR(36) NOT NULL DEFAULT '',
    updated_at   BIGINT       NOT NULL,
    deleted      INT      NOT NULL DEFAULT 0,
    INDEX idx_user_tx_user_updated (user_id, updated_at)
);

CREATE TABLE user_account (
    id             VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id        BIGINT      NOT NULL,
    name           VARCHAR(32) NOT NULL,
    type           VARCHAR(16) NOT NULL,
    initial_balance DOUBLE     NOT NULL DEFAULT 0,
    is_default     INT     NOT NULL DEFAULT 0,
    sort_order     INT         NOT NULL DEFAULT 0,
    updated_at     BIGINT      NOT NULL,
    deleted        INT     NOT NULL DEFAULT 0,
    INDEX idx_user_acc_user_updated (user_id, updated_at)
);

CREATE TABLE user_budget (
    id         VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    scope      VARCHAR(16) NOT NULL,
    category   VARCHAR(32) NOT NULL DEFAULT '',
    amount     DOUBLE      NOT NULL,
    updated_at BIGINT      NOT NULL,
    deleted    INT     NOT NULL DEFAULT 0,
    INDEX idx_user_budget_user_updated (user_id, updated_at)
);
