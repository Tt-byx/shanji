# 闪记 FlashNote · Android 原生版

Kotlin + Jetpack Compose 编写的原生安卓应用，目标 Android 16（API 36），最低支持 Android 8.0（API 26）。
通过 Docker 容器构建，本机无需安装 Android SDK / Android Studio。配套后端见 `../shanji-server`（Spring Boot + MySQL）。

## 构建与安装

### 构建（本机只需 Docker + Git Bash）

```bash
cd shanji-android
bash build.sh
```

- 第一步构建环境镜像 `shanji-builder:1.0`（JDK17 + Android SDK 36 + Gradle 8.11.1），只做一次；
- 第二步在容器里跑 `gradle assembleRelease`（R8 优化 + 资源收缩，真机性能更好；签名复用镜像内固定 keystore，可覆盖安装），Gradle 依赖缓存挂在 `shanji_gradle_cache` 卷里，二次构建很快；
- 产物：`apk/ShanJi-v1.4-release.apk`（约 2.3MB，release 包）。

### 装到手机 / 模拟器

1. 把 `apk/ShanJi-v1.4-release.apk` 传到手机（微信文件传输助手 / QQ / 数据线 / 网盘均可）；
2. 手机上点开 APK → 系统提示「未知来源应用」→ 允许该来源安装 → 安装；
3. 打开「闪记」，首次启动自动载入演示数据（「我的 → 清空全部数据」一键变全新账本）。

> release 签名固定写在构建镜像里，以后重新构建的 APK 可以直接覆盖安装，不用先卸载。

## 功能一览（一期 + 二期）

| 模块 | 功能 |
|---|---|
| 明细 | 月切换、月支出/收入/结余、按天分组流水、账户标签、分享标记、点条目编辑/删除 |
| 记一笔 | 底部弹层：金额、商户、日期时间、8 分类、备注、**支出/收入/转账**、账户选择 |
| 截屏记账 | 微信/支付宝截图「分享 → 闪记」进入确认填单页；AI 多模态识别预填金额商户 |
| 统计 | 周/月切换、总支出环比、近 5 月趋势、分类占比、消费洞察、**预算卡（总预算+分类预算，超支标红）**、日历入口 |
| 日历 | 月视图每日支出热力着色、今日描边、点日期看当日明细 |
| 搜索 | 按商户/备注/分类/金额关键词过滤 |
| 账户 | 微信/支付宝/现金/银行卡四账户、余额与本月收支、账户增删改、**转账（不计入收支统计）** |
| 预算 | 月度总预算 + 分类预算、进度条、超支横幅与记账时 Toast 提醒 |
| 周期记账 | 每月固定日期自动生成账单（房租/话费等），App 启动时补记 |
| 桌面小组件 | 本月/今日支出 + 预算进度，记账后实时刷新 |
| 我的 | 外观三态（跟随系统/浅/深）、应用锁（指纹/人脸 + PIN 兜底）、预算设置、周期记账、AI 设置、导出 JSON/CSV、恢复备份、清空数据 |
| 云同步 | 账号密码注册/登录（预留验证码通道），push→pull→LWW 同步账单/账户/预算，换机重装可找回 |
| 数据 | Room + SQLite（tx.json 旧数据自动迁移并保留 .bak），软删墓碑支持删除云传播 |

## 项目结构

```
shanji-android/
├─ Dockerfile                     构建环境镜像（SDK 36 + Gradle 8.11.1 + 固定 debug 签名）
├─ build.sh                       一键构建脚本
├─ app/schemas/                   Room schema 导出（迁移测试依据）
└─ app/src/main/
   ├─ AndroidManifest.xml         分享入口、桌面小组件 receiver、明文流量放行（本地后端）
   └─ java/com/shanji/app/
      ├─ MainActivity.kt          导航脚手架（5 Tab + 子页面 + 返回栈 + 应用锁）
      ├─ ShareCaptureActivity.kt  分享确认填单页
      ├─ data/
      │   ├─ Model.kt             Tx/Account 领域模型 + TxStore(Room) + AppStore 单例
      │   ├─ db/                  Entity/Dao/Migrations（v1→v4：账户/预算/周期表）
      │   ├─ Ledger.kt / BudgetCalc.kt   统计与预算纯函数
      │   ├─ AccountStore / BudgetStore / RecurringStore
      │   ├─ RecurringScheduler.kt周期记账启动补记
      │   ├─ BackupManager.kt     JSON/CSV 导出导入
      │   ├─ LockManager.kt       应用锁（PBKDF2 PIN + 生物识别）
      │   ├─ SyncManager.kt       登录 + 云同步（push/pull/LWW）
      │   ├─ WidgetUpdater.kt     小组件快照
      │   └─ net/                 Retrofit/OkHttp/DTO（baseUrl 可配置）
      └─ ui/                      Home/Stats/Accounts/Calendar/Search/Recurring/Login/Lock/RecordSheet/BudgetCard/Profile/theme/widget
```

## 云同步（配套 shanji-server）

```bash
cd ../shanji-server
docker compose up --build -d     # MySQL:3306 + API:8080
```

- 模拟器内服务器地址默认 `http://10.0.2.2:8080/`（10.0.2.2 = 宿主机回环），真机改成电脑局域网 IP；
- 登录页有「测试连接」按钮；账号在登录页自行注册。

## 测试（端到端，MuMu + adb）

```powershell
$adb = "D:\Program Files\Netease\MuMu\nx_main\adb.exe"
& $adb connect 127.0.0.1:16384
& $adb install -r apk\ShanJi-v1.4-release.apk
& $adb shell am start -n com.shanji.app/.MainActivity
& $adb shell uiautomator dump /sdcard/ui.xml     # UI 树断言
& $adb shell screencap -p /sdcard/shot.png       # 截图（配合 pull，勿用 PowerShell > 重定向）
```

各模块 E2E 用例与结论见 `../doc/2026-11-21-M*模块小结.md`。

## 常见问题

- **构建时 Docker Desktop 必须在运行**（任务栏鲸鱼图标），否则 `docker build` 报 daemon 错误。
- **Docker 拉镜像/下依赖报 proxy 127.0.0.1:8888 拒绝连接**：本机 Docker 配置了手动代理（Clash Verge），需保持其运行。
- **安装时提示「解析包错误」**：确认手机是 Android 8.0+；传输过程 APK 被截断的话重传一次。
- **想用数据线安装**：手机开「开发者选项 → USB 调试」，`adb install apk/ShanJi-v1.2-debug.apk`。
