# 身份认证选择与上传交互修复（2026-10-07）

## 行为

用户先选择国家、申请人、认证级别和证件类型，初始不预选。身份证要求正面、反面、手持；护照要求个人信息页；新增驾驶证及居留许可，均要求正反面。标准/增强认证的地址证明独立选择，未选择不会出现水电账单上传项。地址证明切换使用独立文件键，避免账单被误用为银行流水。增强认证继续要求人脸证据。

前端复用 DropdownSelect，弹层按触发器上下边界定位并限制可用高度。上传卡片缩小，桌面双列。使用 [React DayPicker](https://daypicker.dev/start) 10.0.2 标准日历，中文/英文、年份月份自定义选择器，内联展开保留日期字段；证件到期日不能早于今天，地址证明签发日仍限制最近三个月。PDF 显示文件图标并可查看，不作为图片渲染。

后端沿用 ComplianceService -> KycDocumentService -> ComplianceKycRepository 的校验和持久化流程，新增类型同步上传白名单、正反面校验、资料引用和数据库约束。迁移 `deployment/migrations/20261007-kyc-identity-document-types.sql`，init.sql 同步。

## 验证

- 隔离 HEAD 构建，未包含工作区其他安全功能改动；HotSpot Corretto JDK 27。
- Maven KycDocumentServiceTest、KycApplicationValidationTest、KycEvidenceReferenceTest、ExternalKycProviderTest、ComplianceServiceTest：28 项通过；网关打包通过。
- 前端 build、lint 通过（lint 输出既有 info 提示）；21 个测试文件、107 项通过。
- Playwright 桌面 1280 浅色 / 手机 390 深色：空默认值、四种证件动态上传项、地址证明独立切换、文件隔离、年月日选择、提交 payload、菜单不遮挡触发器、无横向溢出通过。
- 测试服务器真实 API：新建测试账号，两个新增证件类型均上传成功；缺少反面返回 400，完整资料提交成功；会话已注销。只使用明确标记 SYNTHETIC QA ONLY 的测试文件，保留提交记录用于审计。
- ex.tokdou.com 实际新注册测试账号：中文手机页面、空默认值、驾驶证正反面、日期日历和国家查询通过，会话已注销。
- 本轮未改变移动客户端、真实第三方身份审核、资金/交易逻辑；没有对真实身份资料作认证。

## 发布

后端提交 6c83c67b、08d42c1c，前端 c3597a9、7ec152d。前端 Cloudflare 自动发布。

服务器网关 PID 2029430，SHA-256 `b38a0cc744c0ba58a6e61304df11d3c4677748958ee706c79db89e474baaef1d`，health UP；其他交易服务 PID 未变化。保持原有 30 秒 Aeron 响应超时。部署备份位于服务器 `/var/backups/surprising/20261007-kyc-controls`，含旧 jar 和私有启动参数，供回滚使用。

本轮本地临时构建/浏览器文件路径 `/tmp/kyc-controls-20261007`，验证完成后删除并停止本轮 Vite。上述临时路径仅用于记录历史位置；服务器部署备份保留。
