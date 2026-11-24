# 2026-11-23 DeepSeek 移动端聊天 UI 复刻总结

## 用户的问题

用户提供了一份「Build a mobile-only AI chat interface that replicates DeepSeek's iOS/Android app UI/UX」的英文提示词，要求在 `shanji-android`（Kotlin + Jetpack Compose）中原生复刻 DeepSeek 移动端聊天交互，核心交付三项「phone feel」：

1. **侧滑抽屉**：历史会话由 AlertDialog 改为覆盖式抽屉，手势开合，left/right 位移（禁 transform），约 80vw 宽；
2. **流式揭示**：段落批次淡入（约 520ms），代码块/表格绝不腰斩，稳定 key 不跳动；
3. **两行 44px composer**：输入区两行布局与触摸目标规格。

提示词还包含：触摸目标 ≥44pt/48dp、输入字体 16px（否则 iOS 自动缩放）、safe area、dvh、reduced-motion、气泡 80% 限宽与 ≥4.5:1 对比度、长按气泡 → 底部操作表 + 轻触觉、Back 键先关浮层等。

## 如何分析的

1. **读现有实现**：通读 `ChatScreen.kt`（约 900 行）、`Markdown.kt`、`ChatStreamManager`、吸底滚动逻辑，确认哪些是不能破坏的既有能力（后台流式、断点续传、贴底跟随、回到底部 FAB）。
2. **与用户确认 4 项决策**（AskUserQuestion），把 Web 提示词裁剪为适合原生的方案：
   - 不加麦克风（尾部恒为发送钮）；
   - 流式揭示选「段落批次淡入」模式（替换原逐字打字机）；
   - **不做两行 composer**（保留单行胶囊，仅做规格校验）——原生键盘/布局与 Web 不同，两行是 Web 视口的妥协；
   - 历史会话 AlertDialog → 侧滑抽屉。
3. **拆解风险点**：
   - 抽屉动画若用 `transform: translateX()` 会困住内部 fixed 浮层 → 改用 `Modifier.offset`（布局位移）；
   - 逐字打字机每字符重排导致界面跳动 → 改为「批次只增不合并 + `key(i)` 稳定分段」；
   - 批次切割若切在代码块/表格/行内构造中间会腰斩 → 设计 `nextRevealCut` 安全切割算法；
   - 对比度：Primary #007AFF 白字仅 4.02:1 不达标 → 用户气泡改 PrimaryDeep #0071E3（4.70:1）。

## 如何解决的

### 1. 历史抽屉（`ChatScreen.kt`）
- 状态三件套：`showHistory`（挂载）/ `drawerOpen`（动画目标态）/ 单一 `drawerJob`（open/close 互斥，防半开半关竞态）；
- 动画：`Modifier.offset(x = animateDpAsState(...))` tween(280ms)，遮罩 `animateFloatAsState` 0→0.4，reduced-motion 用 `snap()`；
- 宽度：`screenWidthDp * 0.8f`（80vw 上限，不写死 280px）；
- 手势：主 Column 右滑（>48dp 阈值）开抽屉；遮罩层左滑/点按关；`BackHandler(enabled = showHistory)` 先关抽屉；
- 抽屉挂载时遮罩全屏在上层，主 Column 不在命中路径，不会误触发开抽屉。

### 2. 段落批次揭示（替换逐字打字机）
- 数据结构：`batchCuts: SnapshotStateMap<String, List<Int>>`（切割点列表）+ `batchSettled`（已定稿批次数）；
- `nextRevealCut(text, from, streamDone)` 安全切割：REVEAL_MIN=120 防碎批；围栏（```）与表格整块原子揭示；块边界优先（400 窗口）、句末兜底；`inlineBalanced` 保护行内 `code`/`**bold**`/`[link]` 不被切开；流结束 flush 全量；
- 驱动循环 80ms tick，**上一批 520ms 淡入完成才切下一批**（有界节奏，模拟本地播放队列）；
- 渲染：`key(i)` 稳定分段 + 每段独立 `Animatable` 淡入，旧批字符串永不变化 → 不重排、不跳动；代码块/表格因原子切割永不腰斩；
- 尾批光标：闪烁 `▍`，段尾为 ` ``` ` 或 `|` 时换行拼接，防破坏围栏/表格行结构；
- 会话切换清空批次进度；历史消息按全文 instant 初始化不重播；
- `deletedIds` 集合防止已删消息被流快照回加。

### 3. 长按底部操作表
- `detectTapGestures(onLongPress)` + `LocalHapticFeedback.performHapticFeedback(LongPress)`；
- Material3 `ModalBottomSheet(skipPartiallyExpanded)`：复制（恒显）/ 删除（仅非流式）；
- 补 `BackHandler { sheetTarget = null }`——**E2E 发现 Back 会穿透到 MainActivity 把页面切走**，后组合者优先，修复后 Back 只关表。

### 4. 规格校验（u5）
- 发送钮 44dp → **48dp**；输入框显式 `fontSize = 16sp`；
- 建议 chips padding 13→15dp（≈49dp ≥48dp）；回到底部 FAB 加 `defaultMinSize(minHeight = 48.dp)`；
- `send()`/FAB 滚动按 reduced-motion 降级为 `scrollToBottom()`（跳过平滑）；
- 清理未用导入（animate、ArrowBack），补 `@OptIn(ExperimentalMaterial3Api)`。

### 5. 构建问题修复
- ChatBubble 签名 `SnapshotStateMap<String, Int>>` 多一个 `>` → 语法错误，删除；
- `Settings.Global` 被当表达式赋值（类无伴生对象）→ 改为直接静态调用 `Settings.Global.getFloat(...)`；
- 版本升 versionCode 12 / versionName 1.11，build.sh、README 产物名同步 `ShanJi-v1.11-release.apk`。

## 最后结果如何

**构建**：Docker 构建成功，产出 `apk/ShanJi-v1.11-release.apk`（1.83MB，R8 优化）。

**MuMu E2E 验证（SDK 35，全部通过）**：
| 验证项 | 结果 | 证据 |
|---|---|---|
| 右滑打开抽屉 | ✅ | e2e_02：抽屉滑入，80vw，遮罩变暗 |
| 左滑关闭抽屉 | ✅ | e2e_03：回到对话页 |
| Back 关抽屉不退出 | ✅ | e2e_04 / e2e_12：关抽屉，topResumedActivity 仍为 MainActivity |
| 输入框 16sp + 发送钮点亮 | ✅ | e2e_05：hello 输入，发送钮高亮 |
| 批次揭示日志 | ✅ | logcat：`batch id=7aba8d [0,334)/339 streaming=true` → `[334,471)/471` → `batch settle idx=0/1`，多批 + 520ms 定稿节奏正确 |
| 长按气泡操作表 | ✅ | e2e_07：复制/删除 48dp 行弹出 |
| Back 关操作表 | ⚠️→✅ | 首测发现穿透导航，补 BackHandler 后修复（复测通过） |
| 回到底部 FAB / 吸底滚动 | ✅ | 既有能力未破坏（e2e_06 长文渲染完整） |

**遗留/说明**：
- 两行 composer 与 mic 已按用户决策不做，单行胶囊满足 16sp/48dp 规格；
- 桌面断点完全不涉及（纯手机 App，无此问题）；
- safe area：App 由 Scaffold + imePadding 统一处理（Android 15+ edge-to-edge 已在 MainActivity 处理键盘与导航栏）。

**改动文件**：`ChatScreen.kt`（核心）、`ChatStore.kt`、`ChatEntities.kt`（deleteById）、`build.gradle.kts`、`build.sh`、`README.md`。
