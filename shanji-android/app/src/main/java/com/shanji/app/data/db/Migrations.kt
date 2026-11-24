package com.shanji.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/* DB 1 → 2：新增 accounts 表 + txs 增加账户列 + 4 条默认账户 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `accounts` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`initialBalance` REAL NOT NULL, `isDefault` INTEGER NOT NULL, " +
                    "`sortOrder` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("ALTER TABLE `txs` ADD COLUMN `accountId` TEXT NOT NULL DEFAULT 'acc_cash'")
        db.execSQL("ALTER TABLE `txs` ADD COLUMN `toAccountId` TEXT NOT NULL DEFAULT ''")

        val now = System.currentTimeMillis()
        val defaults = listOf(
            Triple("acc_wechat", "微信", 0),
            Triple("acc_alipay", "支付宝", 1),
            Triple("acc_cash", "现金", 2),
            Triple("acc_bank", "银行卡", 3)
        )
        defaults.forEach { (id, name, sort) ->
            db.execSQL(
                "INSERT INTO accounts (id, name, type, initialBalance, isDefault, sortOrder, updatedAt, deleted) " +
                        "VALUES ('$id', '$name', '${when (id) {
                            "acc_wechat" -> "wechat"
                            "acc_alipay" -> "alipay"
                            "acc_bank" -> "bank"
                            else -> "cash"
                        }}', 0, ${if (id == "acc_cash") 1 else 0}, $sort, $now, 0)"
            )
        }
    }
}

/* DB 2 → 3：新增 budgets 表 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `budgets` (" +
                    "`id` TEXT NOT NULL, `scope` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                    "`amount` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
    }
}

/* DB 3 → 4：新增 recurring 周期记账表 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring` (" +
                    "`id` TEXT NOT NULL, `type` TEXT NOT NULL, `amount` REAL NOT NULL, " +
                    "`merchant` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                    "`accountId` TEXT NOT NULL, `dayOfMonth` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `lastRunDate` TEXT NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
        )
    }
}

/* DB 4 → 5：聊天会话 + 消息（记忆与对话管理） */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `conversations` (" +
                    "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                    "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                    "`role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversationId` ON `chat_messages` (`conversationId`)")
    }
}

/* DB 5 → 6：recurring 升级为「固定支出」单表（F8/F23）——
 * 增加 周期/到期月份/是否自动记账/来源 列，存量周期规则默认 autoRecord=1；
 * 订阅数据从 SharedPreferences 首次启动时导入（见 RecurringStore，属数据迁移非 schema） */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `cycle` TEXT NOT NULL DEFAULT 'monthly'")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `dueMonth` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `autoRecord` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `recurring` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'manual'")
    }
}
