# 2026-11-22 AI 入底部 Tab 与 DeepSeek 风格布局总结

## 用户的问题

1. 首页的 AI 图标应该放进底部 tab，显得和谐；
2. AI 页面布局参考 DeepSeek 手机端设计：从网上下载 DeepSeek 官方 APK，用 GitHub 的 `morluto/rea`（REA）项目逆向分析页面布局。

## 如何分析的

1. **REA 调研**：`morluto/rea` 是逆向分析工具集（MCP + CLI），支持 native 二进制/JS/网站/Android 资源。本机 Node 24.12.0 不满足其要求（≥24.22.1），用 winget 升级到 Node 26.12.1。
2. **下载 DeepSeek APK**：官网下载页是 SPA，从 `main.da36db67e2.js` 里提取出官方直链 `https://download.deepseek.com/apk/deepseek.apk`（21.3MB，v2.9.0，包名 com.deepseek.chat）。
3. **REA 逆向分析**：
   - `analyze` 不支持 APK 归档（Ghidra 只接可执行文件、Hopper 不支持 Windows）；
   - 改用 REA 专门的 `decode-android-resources`（apktool provider）。apktool 2.12.0 不认 `--version`，写 wrapper bat 拦截版本探测（输出纯 `2.12.0` 满足 REA 解析）后成功；
   - 产出：完整 manifest（Compose 应用、`adjustResize`、单 Activity + 深链 + 快捷方式）+ 773 条资源字符串。
4. **从逆向字符串提炼 DeepSeek UI 模式**：
   - 欢迎态：`chat_welcome_title` 居中大标题 + 信息卡（"Welcome to DeepSeek"），非说明段落；
   - 输入框：`chat_input_placeholder_chat` "Type a message" 胶囊输入、按住说话；
   - 会话管理：New chat / Rename / Delete、时间分组（Today/Yesterday/7 Days/30 Days）、空态 "No chat yet"；
   - 消息操作：Copy/Like/Dislike/Regenerate、`go_to_bottom` 回到底部。
   - 也装进 MuMu 截了登录页（无账号进不了主界面，静态逆向补足）。
5. **布局映射**：底部 5 tab 变为 明细/统计/**AI**/账户/我的（AI 居中，原"截屏"tab 的打开弹层功能挪到首页头部相机图标——截屏分享入口 ShareCaptureActivity 本就独立）。

## 如何解决的

### 导航（MainActivity/HomeScreen）
- tabs：移除 `scan`，新增 `TabItem("chat", "AI", Icons.Filled.SmartToy)` 居中第 3 位；
- `ChatScreen` 从子页面改为 tab 页：去掉返回箭头和 onBack 参数，页头改大标题"问账本"（24sp Bold，与其它 tab 一致）+ 历史/新对话图标；
- 首页头部：AI 图标换成 `PhotoCamera`（content-desc "截屏记账"），onCapture 接线打开记账弹层（保持原 tab 行为）。

### DeepSeek 风格布局（ChatScreen）
- **欢迎态**（会话无消息时）：居中 32sp 大标题"问账本"+ 副题"问花费，也管记账"+ 4 条建议 chips（这个月花了多少/外卖花了几笔/记一笔打车 20/哪类花得最多），点击即发送——替代原来的 welcome 气泡；
- **胶囊输入框**：`RoundedCornerShape(999)` + 圆形发送钮（有内容才点亮变 Primary 底白箭头）；
- **内部消息不上屏**：`[系统] 查询结果`（queryTx 回传）只进 AI 上下文，加载时 `visible()` 过滤（顺手修掉的遗留问题）。

## 最后结果

**构建**：BUILD SUCCESSFUL，APK 已更新 `apk/ShanJi-v1.2-debug.apk` + `闪记-服务器版-20261121.apk`；REA/apktool 中间产物已清理。

**E2E（MuMu，全部 PASS）**：

| 用例 | 结果 |
|---|---|
| 底部 tab = 明细/统计/AI/账户/我的，AI 居中高亮 | PASS |
| 首页头部"截屏记账"→ 打开记账弹层（原 tab 功能不丢） | PASS |
| AI tab 打开历史会话，`[系统]` 内部消息隐藏 | PASS |
| 新对话 → 欢迎态大标题+副题+4 条 chips（截图核验，视觉与 DeepSeek 一致） | PASS |
| 点 chip「这个月花了多少」→ 直接发送并流式回复（¥1,706.58） | PASS |
| 回归：统计（11月总支出/上月总结）、账户（总资产）、我的（外观/应用锁/预算设置/周期记账/AI 识别设置/立即同步）、首页（搜索/本月支出/底部 tab） | 全 PASS |

**过程中的坑**：
- REA 的 native provider 不吃 APK 归档，Android 要走 `decode-android-resources`（apktool）；
- apktool 2.12.0 无 `--version` 子命令，wrapper bat 拦截 + 纯版本号输出才过 REA 版本探测；
- Node 版本不满足 REA 引擎要求（≥24.22.1），winget 装 26.12.1；
- 官网下载页是 SPA，APK 直链藏在打包 JS 里；
- uiautomator 对图标按钮要按 `content-desc` 找，不能按 text。

**遗留**：DeepSeek 主界面需登录才能进，本次布局参考 = REA 静态逆向的资源/结构 + 欢迎页截图；如需更精细的对话页细节（气泡圆角/字号），可注册 DeepSeek 账号后再抓一轮。
