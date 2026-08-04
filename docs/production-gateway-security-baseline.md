# 生产网关安全基线 / Production Gateway Security Baseline

## 中文

生产环境必须启用 Spring `production` profile：

```bash
SPRING_PROFILES_ACTIVE=production
```

生产 profile 会在网关启动阶段强制校验以下边界：

- 不允许通过请求头回退使用 `userId`，私有接口必须经过身份认证。
- 管理员必须启用 MFA，并配置管理员来源 IP 白名单。
- 只有来自受信任反向代理 CIDR 的请求才会使用 `X-Forwarded-For`；网关直连时使用 TCP 对端地址，防止伪造转发头绕过白名单。
- JWT 密钥、验证码 pepper、MFA 密钥加密密钥必须使用长度足够且非开发默认值的秘密。
- 邮箱验证必须配置 Resend API key 和发件人；服务地址必须是 HTTPS，默认使用 `https://api.resend.com`。
- custody wallet 必须启用，并配置 API、webhook 以及现货账户服务地址。
- KYC 文档存储必须启用 S3 兼容存储，并配置 endpoint、bucket、region、access key 和 secret key。
- 数据库连接必须通过 `GATEWAY_DB_URL`、`GATEWAY_DB_USERNAME`、`GATEWAY_DB_PASSWORD` 注入。

缺少任一必需配置时，网关会在启动阶段失败，禁止以本地默认值继续运行。生产环境还必须在受信任的反向代理或内部网络后暴露管理端点，并将管理员来源和受信任代理分别配置为实际的 CIDR 白名单。

主要环境变量：

```text
GATEWAY_DB_URL
GATEWAY_DB_USERNAME
GATEWAY_DB_PASSWORD
GATEWAY_JWT_SECRET
GATEWAY_VERIFICATION_CODE_PEPPER
GATEWAY_MFA_SECRET_ENCRYPTION_KEY
GATEWAY_ADMIN_IP_ALLOWLIST
GATEWAY_ADMIN_TRUSTED_PROXY_IP_ALLOWLIST
RESEND_API_KEY
RESEND_FROM
GATEWAY_CUSTODY_WALLET_BASE_URL
GATEWAY_CUSTODY_WALLET_API_KEY
GATEWAY_CUSTODY_WALLET_API_SECRET
GATEWAY_CUSTODY_WALLET_WEBHOOK_SECRET
GATEWAY_SPOT_ACCOUNT_BASE_URL
GATEWAY_KYC_DOCUMENTS_ENDPOINT
GATEWAY_KYC_DOCUMENTS_BUCKET
GATEWAY_KYC_DOCUMENTS_REGION
GATEWAY_KYC_DOCUMENTS_ACCESS_KEY
GATEWAY_KYC_DOCUMENTS_SECRET_KEY
```

不要把真实秘密提交到仓库、镜像或日志中；应通过部署平台的 Secret 注入机制提供，并在轮换时同步更新对应的密钥版本。

## English

Production deployments must enable the Spring `production` profile:

```bash
SPRING_PROFILES_ACTIVE=production
```

The profile validates the gateway security boundary at startup. It rejects user-ID header fallback, requires authenticated private routes and administrator MFA, requires administrator and trusted-proxy CIDR allowlists, only accepts `X-Forwarded-For` from a trusted proxy, rejects development secrets, and requires configured Resend, custody-wallet, spot-account, database, and S3-compatible KYC storage dependencies.

If any required value is missing or still uses a development default, startup fails closed. Keep administrative endpoints behind a trusted reverse proxy or private network and set both CIDR allowlists to the real network ranges. Inject secrets through the deployment platform; never commit, bake, or log them.
