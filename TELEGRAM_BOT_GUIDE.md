# Surprising-Ex Telegram Bot 全面支持与集成指南

本文档全面阐述 `surprising-ex` 交易系统对 Telegram Bot 的全面支持、系统架构、交互菜单规范、安全二次确认机制及生产部署指南。

---

## 一、 系统架构与双模设计

本项目 Telegram Bot 采用 **「原生 Bot 菜单/键盘交互」 + 「Telegram Mini App (TMA) 深度交易终端」** 的双模设计，既满足用户在聊天界面下的极速资产查询、行情浏览与闪电开平仓，又支持深度图表与专业委托。

```mermaid
flowchart TD
    User["Telegram 用户"] --> TG["Telegram Bot (@surprising_ex_bot)"]
    
    subgraph Gateway ["surprising-gateway (Spring Boot)"]
        Webhook["Webhook 接入与验签\n(/api/v1/telegram/bot/webhook)"]
        Polling["Long-Polling 守护线程\n(TelegramBotPollingService)"]
        Dispatcher["TelegramUpdateDispatcher\n(指令 & 回调路由分发器)"]
        Session["TelegramSessionService\n(多步会话状态机 & 定时清理)"]
        SecSvc["TelegramBotSecurityService\n(二次安全校验策略)"]
        BotClient["TelegramBotClient\n(富文本/Inline键盘/图片发送)"]
        TMA_Auth["TelegramWebAppInitDataVerifier\n(/api/v1/auth/telegram/webapp/login)"]
        
        subgraph Business Handlers
            H_Auth["TelegramAuthHandler (免登与一键注册)"]
            H_Asset["TelegramAssetHandler (资产总览与账户看板)"]
            H_Market["TelegramMarketHandler (实时盘口与价格行情)"]
            H_Pos["TelegramPositionHandler (合约持仓 & 100%/50%分档平仓)"]
            H_Order["TelegramOrderHandler (活跃挂单查询 & 一键全撤)"]
            H_Trade["TelegramTradeHandler (快捷下单 & Mini App 入口)"]
            H_Deposit["TelegramDepositHandler (公链地址 & 二维码推送)"]
            H_Withdraw["TelegramWithdrawHandler (提币状态机向导)"]
            H_Transfer["TelegramTransferHandler (现货/合约闪电划转)"]
            H_Settings["TelegramSettingsHandler (安全确认级别与PIN码)"]
        end
    end

    TG -->|POST Webhook| Webhook
    TG -->|Long Polling 拉取| Polling
    Webhook --> Dispatcher
    Polling --> Dispatcher
    Dispatcher --> Session
    Dispatcher --> SecSvc
    Dispatcher --> Business Handlers
    Business Handlers --> BotClient
    BotClient -->|REST API| TG
    User -->|打开 Mini App| TMA_Auth
```

---

## 二、 交互菜单与功能矩阵

### 1. 账号免登与自动绑定
- **原生 Bot 免登**：利用底层的 `gateway_telegram_identities` 表。用户在 Bot 发送任意消息或点击 `/start`，系统通过 Telegram User ID 自动关联已有用户；若用户首次使用，系统自动为用户创建平台 UID 并完成绑定，实现“零感知免登”。
- **Telegram Mini App (TMA) 零跳转免登**：
  - 接入端点：`POST /api/v1/auth/telegram/webapp/login`
  - 前端在 Mini App 内调用 `Telegram.WebApp.initData` 提交，后端基于 HMAC-SHA256 官方标准验签并自动发放平台 JWT Token，无需任何外部浏览器跳转！

### 2. 菜单体系与指令一览

| 指令 / 菜单文本 | 回调事件前缀 | 功能说明 | 核心服务与依赖 |
| :--- | :--- | :--- | :--- |
| `/start` / `/menu` / `主菜单` | `menu:main` | 展示主控面板，提供常驻 Reply 键盘与 Inline 快捷面板 | `TelegramAuthHandler` |
| `/assets` / `💰 账户资产` | `menu:assets` | 查询净资产折合 (USDT)、现货账户、U本位合约、币本位合约分布明细 | `AccountService.balances` |
| `/market` / `/price` / `📊 实时行情` | `menu:market` | 主流币对（BTC/ETH/SOL）盘口最佳买卖价、中间参考价、价差看板 | `MatchingMarketDataService` |
| `/positions` / `📈 合约持仓` | `menu:positions` | 查看所有未平仓位（多空方向、均价、持仓量），支持 **⚡ 市价全平 100%** 与 **⚖️ 平半仓 50%** | `OrderService.closePosition` |
| `/orders` / `📋 当前挂单` | `menu:orders` | 查看当前未成交的委托挂单，支持单笔撤单与 **⚠️ 一键全撤 (Cancel All)** | `OrderService.openOrders` / `cancelOpenOrders` |
| `/trade` / `⚡ 快捷交易` | `menu:trade` | **双模交易**：一键打开 Mini App 交易终端 / Bot 内快捷市价开多做空 | `OrderService.placeCommandAsync` |
| `/deposit` / `💳 充值` | `menu:deposit` | 支持 TRON (TRC20)、ETHEREUM (ERC20)、BITCOIN 等主网选择，**ZXing 动态生成 PNG 二维码**推送到聊天窗 | `CustodyWalletClient` + `TelegramQrCodeService` |
| `/withdraw` / `💸 提币` | `menu:withdraw` | 多步状态机引导（网络选择 ➔ 输入目标地址 ➔ 输入金额 ➔ 手续费预估 ➔ 二次确认） | `CustodyWithdrawalService` |
| `/transfer` / `🔄 划转` | `menu:transfer` | 现货账户 ⇄ U本位合约 ⇄ 币本位合约 之间的免手续费秒级划转 | `ProductTransferCoordinator` |
| `/kol` / `🤝 邀请返佣` / `推广` | `kol:dashboard` | **KOL / 社区合伙人推广主控台**：收益看板、直推/裂变人数、一键分享、海报生成、层级树与下级分成管理 | `TelegramAffiliateHandler` + `AffiliateService` |
| `/settings` / `⚙️ 设置` | `menu:settings` | 查看与切换 Bot 操作二次确认防线，设置/修改 6 位交易密码 | `TelegramBotSecurityService` |

---

## 三、 KOL 与邀请返佣 Telegram 深度集成闭环

针对 Telegram 强社群、强裂变特性，系统深度融合了 `surprising-ex` 的邀请返佣系统，提供一站式推广管理能力：

### 1. Telegram Deep Link 裂变免登绑定
- **专属裂变链接**：格式为 `https://t.me/{BOT_USERNAME}?start=aff_{INVITE_CODE}`
- **零阻碍转化**：新用户在群组或好友聊天中点击链接，直接进入 Bot 并自动触发带参数的 `/start` 指令。
- **自动绑定与发券**：系统自动调用 `affiliateService.register()` 与 `affiliateService.accept(code)`，毫秒级为新用户建立绑定关系，立即享受手续费返现与新人特权。

### 2. 团队层级架构树 (`kol:hierarchy`)
- 清晰直观展示合伙人自顶向下的分销网络：
  - **👑 顶层节点**：当前 KOL UID 与其基准分成比率（如 40% 最高返佣）；
  - **Level 1 一级直属下线**：直属受邀人数、平均分成比例；
  - **Level 2 二级间接裂变**：间接裂变人数与团队网络总规模。

### 3. 下级成员个人成绩看板 (`kol:members` & `kol:member:{UID}`)
- 支持分页查看所有直属受邀下级成员。
- 点击任意成员进入**【下级成员专属管理面板】**，可实时查看：
  - 成员 UID 与加入时间；
  - 累计为我贡献的返佣收益 (USDT)；
  - 成员产生的总成交额估算；
  - 当前分配给该成员的手续费分成比例。

### 4. 动态调整下级分成比例 (`kol:do_share:{UID}:{PPM}`)
- 合伙人可在 Bot 中直接一键从自己的佣金中切分一部分给重点下级作为激励扶持：
  - **快捷档位**：支持一键切分 `5%`、`10%`、`20%`、`30%`、`50%`；
  - **自定义比例**：点击 `✏️ 自定义输入比例`，进入会话状态机 `KOL_AWAITING_CUSTOM_SHARE`，直接在聊天框输入任意 0-80 的整数百分比；
  - 底层调用 `AffiliateService.share(viewer, child, ppm)`，实时完成分成比率更新与持久化。

### 5. 创建不同返现比例的渠道码 (`kol:create_code`)
- 支持合伙人为不同社群、渠道创建差异化返佣策略的邀请码：
  - **0% 返现码**：佣金 100% 自留，适合普通散客推广；
  - **10% 返现码**：给好友返现 10%，自留 90%，高转化黄金比例；
  - **20% 返现码**：给好友返现 20%，适合重点渠道合伙人；
  - **30% 返现码**：超高让利，专用于大客户挖角与渠道置换；
- 生成后支持**一键转发到 TG 群组 / 好友**（带预设高转化文案），以及**动态生成 ZXing 二维码海报**。

---

## 四、 用户安全二次确认机制

针对 Telegram Bot 上的敏感资产操作（如提币、开仓交易），系统提供 3 级安全防线，用户可在 Bot `/settings` 或 Web 控制台按需选择：

1. **`CONFIRM_ONLY`（快速确认模式）**：
   - 默认模式。操作提交前，Bot 弹出带有详细参数的防手滑 Inline 按钮（`[✅ 确认提交]` / `[❌ 取消]`）。
2. **`TRADE_PASSWORD`（交易密码验证模式）**：
   - 用户需在 Bot 或 Web 端初始化 6 位纯数字交易密码（密码通过加盐 SHA-256 哈希存储在 `gateway_user_telegram_security_settings` 中）。
   - 提币或下单时，必须在聊天框回复正确的交易密码方可放行。
3. **`OTP_VERIFY`（动态验证码模式）**：
   - 提币或下单时，系统要求输入绑定的 Google Authenticator (TOTP) 或注册邮箱收到的 6 位验证码。

---

## 四、 配置文件与环境变量

在 `surprising-ex/surprising-gateway/src/main/resources/application.yml` 中已配置如下配置项：

```yaml
telegram:
  login:
    client-id: ${TELEGRAM_CLIENT_ID:}
    client-secret: ${TELEGRAM_CLIENT_SECRET:}
    redirect-uri: ${TELEGRAM_REDIRECT_URI:}
  bot:
    token: ${TELEGRAM_BOT_TOKEN:}
    username: ${TELEGRAM_BOT_USERNAME:surprising_ex_bot}
    webhook-url: ${TELEGRAM_BOT_WEBHOOK_URL:}
    webhook-secret: ${TELEGRAM_BOT_WEBHOOK_SECRET:}
    webapp-url: ${TELEGRAM_BOT_WEBAPP_URL:https://app.surprising.exchange}
    polling-enabled: ${TELEGRAM_BOT_POLLING_ENABLED:false}
```

### 生产部署与开发调试说明：
- **模式 1：Webhook 生产模式（推荐）**：
  配置 `TELEGRAM_BOT_WEBHOOK_URL` 和 `TELEGRAM_BOT_WEBHOOK_SECRET`，启动后调用 `POST /api/v1/telegram/bot/setup-webhook` 或通过 curl 向 Telegram 注册。
- **模式 2：Long-Polling 本地/私网秒活模式**：
  当不配置 `TELEGRAM_BOT_WEBHOOK_URL` 或设置 `polling-enabled: true` 时，系统自动启动 `TelegramBotPollingService` 守护线程长轮询，开箱即用，无需配置公网穿透！

---

## 五、 自动化测试验证

所有新增模块均配备单元与集成测试，涵盖：
- WebApp `initData` HMAC-SHA256 官方算法校验与防篡改测试
- Webhook 验签安全防御测试（非法 Secret 拦截）
- 核心分发器命令与回调路由测试（涵盖挂单、撤单、分档平仓）
- 会话状态机多步输入流转与定时清理测试
- 安全确认模式与 6 位 PIN 码哈希验证测试
- ZXing 二维码二进制生成有效性测试

执行测试命令：
```bash
mvn -f surprising-ex/pom.xml test -pl surprising-gateway -Dtest="Telegram*Test"
```
测试结果：**全部通过（BUILD SUCCESS，50 Tests Passed）**。
