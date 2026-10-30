# 公告、协议与多语言完整闭环

目标保持原范围：富文本公告与图文音视频的真实 R2 上传；后台发布、撤回、语言和权限管理；Web / iOS / Android 正常展示；自有协议及注册登录等正确入口；至少十种完整翻译，且各语言公告可独立配置。通过前保持目标 active。

## 当前审查证据

- 公告具备草稿、乐观锁、审批发布、定时投放、撤回、权限、审计、产品线/客户端投放及本人已读记录。
- 后台 `AnnouncementsPage` 使用 textarea 和纯文本预览，只有 zh-CN/en-US；Web 直接渲染字符串，App 使用 SelectableText。尚不具备文章内图文音视频闭环。
- Web 现有 1479 条中英文词条；App 的 ClientLanguage 只有中英文，clientText 为二选一。没有可证明完整的十语种支持。
- 暂未发现完整协议内容、协议管理和版本化同意链路。运营主体、适用辖区、正式域名与联系邮箱已向用户核实，未明确前不虚构或对外发布法律实体信息。
- 已有 C2C 私有 R2 存储，不能把身份/交易私有附件的桶或对象直接公开。公告媒体需独立配置、权限、对象路径和内容类型校验。
- 已读四端 AGENTS；后端 master，三个客户端 main。已有未跟踪 AERON_CORE_REVIEW.md、后台 .codegraph/ 保留。

## 实施与验证清单

- [ ] 统一 12 种语言：English、简体中文、繁體中文、Español、Français、Deutsch、Português (Brasil)、Русский、العربية、日本語、한국어、हिन्दी；处理持久化、语言协商、日期格式和 RTL。
- [ ] 所有受影响用户界面译文完整，检查词条缺失、占位符、复数、错误信息及真实页面；不能用重复英文或通用回退冒充翻译。
- [ ] 后台富文本编辑：格式、列表、链接、图文、音视频、插入位置、可取消上传、错误重试、预览、语言标签/完整性、保存/发布/撤回/审计。
- [ ] 媒体：管理员鉴权、R2 实际写入与回读、体积/MIME/文件内容检查、服务端生成对象键、上传失败清理、公共文章媒体与私有业务附件隔离。
- [ ] 持久化格式与安全：明确 HTML/纯文本契约、服务端允许列表清洗、防脚本/事件/危险 URL；历史纯文本保留文本语义。
- [ ] 公告完整生命周期：目标语言、平台、产品、时间、读状态、更新、撤回、过期、并发冲突和失败恢复；真实数据库联调。
- [ ] Web：公告中心、横幅/弹窗媒体、长文、错误/加载/空状态、明暗主题、桌面/窄屏、切语言与反复进出。
- [ ] iOS / Android：真实模拟器或设备点击与音视频/图片加载、外链、返回、字体放大、明暗主题、布局、弱网、资源释放；不能以 Widget 测试代替。
- [ ] 自有法律内容：用户服务、隐私、Cookie、交易风险、衍生品风险、费用与资金/提现、AML/KYC、投诉处理等；覆盖真实已提供的业务，不声称不存在的资质。
- [ ] 后台协议管理：类型、语言、修订、草稿、预览、发布/撤回、当前有效版本、审计、必要运营信息和冲突检查。
- [ ] 协议入口：注册/登录、第三方登录、网站页脚/法律中心、App 帮助与设置、相关高风险产品；明确可点阅读和主动同意。
- [ ] 版本化同意：服务端验证当前版本、记录用户/文档版本/语言/时间；协议更新后的必要重新确认与失败重试；不把隐私告知和可选营销混为一项默认同意。
- [ ] 合同/API/OpenAPI、迁移、中文 README 同步；后端受影响测试与真实数据库、后台 lint/test/build、Web lint/typecheck/test/build、Flutter analyze/test 及全部交互验证通过。
- [ ] 归档逐项证据并清理本轮临时进程、设备和 QA 媒体；只提交已验证、授权的文件并推送。按完整目标审计后才标记 complete。

## 参考来源

只参考文档分类和需覆盖的主题，正文自行起草，不复制第三方条款或运营实体。

- OKX Terms of Service: https://www.okx.com/en-gb/help/terms-of-service
- OKX Privacy Notice: https://www.okx.com/help/privacy-policy-statement
- OKX U.S. Risk & Compliance Disclosures: https://app.okx.com/en-us/help/us-risk-and-compliance-disclosures
- Jsoup Safelist/Cleaner: https://jsoup.org/apidocs/org/jsoup/safety/Safelist
- Tiptap editor extensions: https://tiptap.dev/docs/editor/core-concepts/extensions
