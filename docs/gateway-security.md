# 网关安全与部署

公网 HTTP/WebSocket 入口只经过 Gateway。Spring Security 在 Gateway 工作，不进入交易 Core，也不替代业务侧的资金、所有权、冻结、权限、审批和幂等检查。

## 请求与身份边界

- `SecurityFilterChain` 默认拒绝未登记的入口；不启用表单登录、HTTP Basic 或 Servlet 登录会话。JWT 从既有数据库读取当前账户、角色及会话状态，同一 HTTP 请求只验证一次身份，产品/批量确定后扣除业务额度。
- 原生和兼容 API 的 HMAC 继续由协议适配器按原始方法、路径、查询及实际正文验证。API Key 与 Bearer 不得混用。Scope、IP、用户状态和资源所有权仍逐项执行。
- 拒绝路径中的百分号编码、分号参数、重复斜线、反斜线和非规范路径。当前路由段只使用 ASCII ID；查询内容仍可以正常 URL 编码。不要在代理层把不同路径改写成同一个受保护路径。
- 管理 IP/权限过滤器位于安全链内；未登记管理操作默认拒绝。用户读取的服务方法另查细粒度权限；修改用户角色会撤销目标用户的会话，重新登录后才获得新权限。
- `/actuator/**`、内部业务入口仅接收本机连接；公网 Nginx 同时阻断内部路径。管理 API 还要求管理员角色、对应权限和配置的 IP 来源，高风险审批继续生效。
- WebSocket JWT 认证、私有订阅和周期复查均检查连接来源 IP；会话撤销、账户冻结、IP 禁用、授权到期均停止私有连接。API Key 仍使用一次性挑战、READ 权限和持续 Key/IP 复查。

## 浏览器会话

用户访问令牌默认 15 分钟，管理访问令牌 5 分钟。用户刷新会话默认 30 天；管理刷新会话最多自首次认证起 8 小时，刷新不能延长这个期限。生产配置禁止超过这些上限及关闭安全 Cookie。

Web/Admin 的访问令牌只留在页面内存；刷新令牌不返回给浏览器 JavaScript。生产 Cookie 使用 `__Host-` 前缀、Secure、HttpOnly、SameSite=Strict、Path=/、不指定 Domain，避免其他子域伪造同名凭证。两个应用的刷新及 CSRF Cookie 各自独立。普通 API 仍用 Authorization 请求头，不使用 Cookie 自动认证。

1. 请求 `GET /api/v1/auth/csrf`，携带 `X-Auth-Client: web` 或 `admin`，并允许凭证；响应 JSON 的 `token` 作为后续 `X-XSRF-TOKEN`。
2. 浏览器登录/注册/二次验证/第三方登录携带 `X-Auth-Transport: cookie`、同一 `X-Auth-Client` 和 CSRF 头。成功后响应 `browserSession=true`、`refreshToken=null`。
3. 刷新和退出分别调用 `POST /api/v1/auth/browser/refresh`、`/logout`，无需在正文提供刷新凭证。页面重载通过 Cookie 恢复会话。
4. 支持 Web Locks 的现代浏览器才开放 Cookie 登录；各浏览器应用使用 Web Locks 串行执行 Cookie 身份操作，避免旧刷新响应覆盖新登录 Cookie；仍核对用户身份，丢弃旧用户的迟到响应。退出失败明确显示错误，不伪装成功。

页面按剩余有效期安排续期，最多提前 30 秒、短令牌在剩余时间过半后刷新，并保留至少 1 秒间隔，避免短于 30 秒的令牌触发连续刷新。

`security.admin-browser-allowed-origins` 单独限制管理 Cookie 操作。生产默认 `https://ex-api.tokdou.com`；用户站即使在 REST CORS 名单中，也不能借用管理 Cookie 获取管理访问令牌。单独的管理域登记在管理来源名单中，REST CORS 自动合并该名单。来源必须是完整的 Origin，无路径或通配符。

移动端继续使用显式刷新令牌协议，并保留安全存储。登录、刷新等凭证接口不发送旧 Bearer，私有身份操作仍发送访问令牌，避免过期令牌在安全链提前阻断刷新。Web 与 API 须部署在同站 HTTPS 域或由同源代理访问；跨站预览域不能依赖 SameSite=Strict Cookie。仅本机 HTTP 开发可设 `GATEWAY_BROWSER_COOKIE_SECURE=false`；生产校验拒绝此配置。

密码直接使用 PBKDF2-HMAC-SHA256、600000 次、随机 16 字节盐、256 位结果；旧轮数不再验证，也不做登录迁移。未知用户也进行密码派生。本机密码计算最多并发 4 个，超额立即 503，不排队占满交易请求。

## 限流、预算与输入限制

原有 IP → 用户/Key/产品/业务桶继续工作，多 Gateway 共用 Valkey；撤单及回执保留独立的全局预算、本机并发和业务额度。达到额度返回 429 与 Retry-After；容量不足或存储故障返回 503，不放行绕过。金融写操作不自动重试未知结果。

| 防护 | 默认策略 |
| --- | --- |
| 普通全局请求 | 2000/s，突发 4000；撤单和回执分别保留 500/s、突发 1000 |
| 登录 IP/标识 | IP 1/s、突发 5；规范化标识 5/min、突发 5 |
| 注册 IP | 5/hour、突发 5；刷新另有 10/s、突发 20，避免注册耗尽刷新额度 |
| 验证接口 IP | 10/min、突发 10 |
| 邮件目的地 | 跨用途共享 60 秒冷却、每 UTC 小时 5 次、每日 20 次 |
| 邮件提供商 | 平滑速率 10000/day、突发 100；另有 UTC 日硬预算 10000 次发送尝试 |
| 上传 | 用户每天 100 个、100MiB；全局每天 1000000KiB；新文件登记才扣预算 |
| 文件确认 | 用户 20/min、突发 5；全局 10/s、突发 20，限制重复读取/扫描 |
| 上传资源 | 单机并发 4 个，扫描并发 2 个；扫描有覆盖连接、写入和读取的总截止时间 5 秒 |
| JSON | 正文 1MiB、嵌套 64 层、单字符串 256KiB、数字 64 字符、字段名 128 字符 |

速率使用令牌桶；邮件小时/日及上传日预算使用 Valkey TIME 的固定 UTC 窗口。一次 Lua 先检查全部相关桶/预算，再原子扣除；拒绝不部分扣费。计数器有 TTL，键不保存原始邮箱或登录标识。配置不是经过压测得到的容量承诺，上线前按实际资源制定容量。

KYC/C2C 保留尺寸、签名、MIME、文件大小、所有权和哈希校验。KYC 图片另限单边 16000、总像素 4000 万。ClamAV 返回明确 clean 才发布文件；异常、未知回复、超时、病毒均拒绝。KYC 先扫描再写存储；C2C 预签名对象保持私有、未 READY，确认哈希并扫描后才能访问。

App 每 20 秒发送应用级 ping，续 Gateway 的连接租约；协议级 ping 不替代这一业务心跳。关闭、断开或重连会取消旧计时器。

## Nginx、静态资源和外部部署

安装入口配置及两份 snippet，保持同一提交版本：

```bash
install -d -m 0755 /etc/nginx/snippets
install -m 0644 deployment/test-single-node/surprising-api-proxy.conf /etc/nginx/snippets/
install -m 0644 deployment/test-single-node/surprising-admin-headers.conf /etc/nginx/snippets/
# 按现有站点布局安装 ex-api.nginx.conf，校验证书路径后执行 nginx -t。
```

入口在 server 层统一限制请求/连接，包含 health location；`/healthz` 只允许 127.0.0.1/::1，并透传真实 readiness 状态。监控/LB 应通过私网或本机探测，不要恢复公网无限制探针。普通正文 1MB，仅 KYC/C2C 上传路径放宽至 20MB；请求头、请求体、发送和 keepalive 均有超时。

代理清除供应商来源头和调用方转发链，只转发经过 Nginx 判定的真实地址；若前面有 CDN/LB，必须按其可信网段配置 real_ip，再执行 IP 策略。不能信任任意 CF-Connecting-IP/X-Forwarded-For。安全访问日志只记录方法、规范路径、状态和请求 ID，不记录查询、Authorization、Cookie、Secret 或验证码；普通上游故障从脱敏访问日志及应用指标排查。

用户站 `public/_headers` 随 Cloudflare Workers Assets 发布 CSP/HSTS/nosniff/Referrer/权限策略；管理端使用 Nginx snippet。CSP 保留现有 Google 登录、Sumsub、字体和私有预览需要的有限来源；Google 弹窗使用 same-origin-allow-popups。更换 API 或第三方域时同步改对应来源。管理端静态产物构建使用 `npm run build -- --base=/admin/`。

生产必须部署并更新 ClamAV 病毒库：TCP 仅监听本机/受控私网，配置 `StreamMaxLength` 不小于上传上限，保持 freshclam 更新和存活告警。Gateway 生产默认启用扫描；扫描服务未就绪时上传保持拒绝。扫描协议和拒绝路径验证不等于已验证生产病毒库覆盖率。

应用代码不能挡住带宽耗尽、SYN/TLS 洪泛或大规模分布式 CC。上线还需 CDN/WAF/DDoS/Bot 规则、源站 IP 限制和专属源站证书/mTLS 或 Tunnel；管理域限制 VPN/零信任；数据库/Valkey/Kafka/Core 不公开监听。浏览器挑战只用于适合人机交互的入口，不给签名 API、WS、Webhook 强制 JS 挑战。生产 DNS、证书、云防火墙及第三方提供商规则需要在实际环境部署验收。

关注入口/应用 429、503、登录失败、邮件预算、扫描不可用、Valkey 超时、数据库池和 Core 队列；保留 `gateway.limits.rejected`、`gateway.limits.unavailable` 等低基数指标。不要把 IP/用户/Key/邮箱作为指标标签，也不要逐次记录攻击请求正文。

## 依赖与验证

Tomcat、PG 恢复使用 Spring Boot 管理的修复版本；BC/LZ4/Snappy 由父 POM 管理安全版本。Web 锁定直接依赖及有安全修复的间接依赖，开发服务器默认只监听 loopback。发布前对实际 SBOM/锁文件复查公告和凭证泄露，扫描不可用不能当作零漏洞。

```bash
mvn -nsu -f surprising-gateway/pom.xml -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom
python3 scripts/security-audit.py --bom surprising-gateway/target/bom.json \
  --npm-lock ../surprising-ex-web/bun.lock --npm-lock ../surprising-admin-web/pnpm-lock.yaml \
  --output /tmp/surprising-security-audit.json
```

脚本查询 OSV 与 npm 官方公告接口；退出码 0 表示本次查询完整且无命中，1 表示存在命中，2 表示扫描不完整。脚本需要 Python 3 和 Bun，不读取应用凭证。后台的间接依赖覆盖配置位于 `pnpm-workspace.yaml`，符合 [pnpm 11 配置位置](https://pnpm.io/11.x/configuring)。

本轮具体命令、结果、范围与未测项见 [安全验收记录](validation/security-hardening-20261029/README.md)。没有压测、全量测试或生产防攻击能力声明。
