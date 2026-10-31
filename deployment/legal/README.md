# 协议内容与上线顺序

这套原创协议覆盖用户协议、隐私政策、Cookie、交易风险、衍生品风险、资金与提币、费用、身份与合规、投诉、安全十类。主体及邮箱按用户要求复用 social：河南朔望科技有限公司、support@surprising.top；交易网站仍为 https://ex.tokdou.com。语言不代表产品可用地区或经营许可，不能把 social 产品的条款或其他交易平台的牌照声明直接搬过来。

`source/` 保存各语言完整正文，按段落结构核对译文；CMS 中的已发布修订不可改写。文本用于管理后台复核，并不证明经营资格。正式发布前应结合实际服务地区、许可、托管、数据所在地和服务商清单核对。原始中英文为本项目编写，其余译文须核对资金、杠杆、法律权利与隐私语义后发布。参考产品文档分类来自 [OKX 条款](https://www.okx.com/en-gb/help/terms-of-service)、[隐私声明](https://www.okx.com/help/privacy-policy-statement)和[风险披露](https://app.okx.com/en-us/help/us-risk-and-compliance-disclosures)，未复制其运营主体或牌照。

中国大陆相关业务限制以现行[银发〔2026〕42号通知](https://www.csrc.gov.cn/csrc/c100028/c7614318/content.shtml)等适用规定为依据，旧银发〔2021〕237号通知已被该通知废止。不能仅因用户接受条款，就认定受限制的服务可以提供。

1. 依次应用 `20261031-content-media.sql`、`20261031-legal-documents.sql`、`20261031-content-media-references.sql`。全新环境的 `init.sql` 已含结构。
2. 新版 gateway 提供 CMS 后，管理员以后台客户端登录。后台角色不因尚未发布用户条款而失去管理入口，普通用户没有此例外。
3. 执行 `python3 deployment/legal/import-drafts.py` 校验完整内容包。仅校验不访问服务器。
4. 通过环境变量提供管理员访问令牌，执行 `python3 deployment/legal/import-drafts.py --url https://实际API域名 --apply` 导入草稿。变量名为 `LEGAL_ADMIN_TOKEN`，不要把令牌写到代码、聊天、提交或命令参数。脚本不发布、不覆盖既有修订；同一内容包按摘要生成稳定修订标识，可重复运行。
5. 在后台“协议管理”逐语言预览，核对全部十二种译文、说明操作原因，使用审批中心的批准记录发布。现行用户协议和隐私政策均发布后，才能开放普通用户注册及登录。
6. Web/App 从公开接口读取现行修订及实际阅读语言，勾选默认关闭；注册、密码、MFA、Google、Telegram 及会话刷新以 `X-Legal-Acceptance: 修订ID:语言,...` 记录确认。新条款在后续登录或刷新时要求重新确认。旧条款及原确认时间保留，拒绝过期修订；隐私确认不等于同意可选营销或任意跨境处理。

先在预发布环境完成上述发布与确认流程，再切换普通用户流量。未发布必需条款时返回 503；已发布但尚未确认当前修订时返回 428 `LEGAL_CONSENT_REQUIRED`。后台发布审批的 428 是另一种操作条件，客户端不能混为用户条款确认。

公开 R2 媒体使用项目专用桶 `surprising-ex-public` 和 `https://ex-cdn.tokdou.com`，路径为 `public/exchange/content/`；私人附件不能移入此桶。桶位于默认 jurisdiction、APAC，Standard 存储，自定义域名最低 TLS 1.2，r2.dev 保持关闭。跨域规则在 `deployment/content-r2.cors.json`，只允许交易 Web 与 API 来源的 GET/HEAD 和媒体 Range 请求。

Gateway 使用仅授予该桶“对象读写”的独立 S3 密钥；不要复用 Social 或私有附件的密钥。真实凭据按用户要求暂存仓库外的 `~/.config/surprising-ex/content-r2.env`（权限 `0600`），运行时注入 `GATEWAY_CONTENT_R2_ACCESS_KEY_ID` / `GATEWAY_CONTENT_R2_SECRET_ACCESS_KEY`，启用及 endpoint 同文件提供。桶和 CDN 域名仍直接配置在 `application.yml`；真实密钥不写入可提交的 YAML、示例或日志。空值示例见 `deployment/content-r2.env.example`，启动加载方法见 Gateway README。该凭据属于创建它的用户，CLI OAuth 登录令牌不交给 Gateway。

保存文章时同事务保留引用，历史协议继续保留媒体。单独线程每小时最多回收二十个已超过二十四小时、没有文章引用的上传；数据库行锁与外键保护并发保存，删除失败留待重试，不扫描其他桶。后台、Web、App 必须配套使用新的 CDN 限制；本轮只创建专用存储资源及更新代码，未发布生产应用。
