# KYC 测试服务器部署验收（2026-10-07）

本次发布网关的 KYC 服务商会话、模拟结果、签名回调和会话刷新接口，并增加 `gateway_kyc_provider_events` 幂等事件表。构建源为 master 已提交基线与 KYC 相关改动，不包含工作区未完成的安全中心、公告或核心改动。

## 验证

- HotSpot Corretto JDK 27，Maven 3.9.16。
- 网关及依赖模块构建成功。ComplianceService、ComplianceValidation、ExternalKycProvider、KycDocumentService、AdminComplianceController、GatewayProductionSecurityConfiguration 共 33 项测试通过。
- 测试服务器 SQL 迁移成功；网关 SHA-256：`8b5ee7b0267b38405cc46623d16af231a9efcfecd02713b40ce02e7543f03d44`。
- 首次启动因费率初始化临时 NOT_CONNECTED 自动回滚。核心独立 metrics 探测正常；再次部署成功，健康接口 200。
- 独立测试用户 26：服务商查询 200；提交→PENDING→模拟拒绝→查询 REJECTED；重新提交→模拟通过→查询 VERIFIED；重复通过返回 403；非法国家参数返回 400。
- 撮合核心、行情、做市、价格与衍生品生命周期进程 PID 均未变化。未操作真实用户持仓或交易订单。
- 服务器备份及回滚材料：`/var/backups/surprising/20261007-kyc`，私有启动参数仅保存在服务器 0600 文件。

## 范围

当前为测试环境 SUMSUB 模拟模式，不会因模拟审核结果发送真实通知。真实第三方证件、人脸及生产回调尚未验收，不将模拟通过当作真实身份认证。数据库持久化通过服务器真实 API 流程验证；独立 JDBC 测试未运行。

## 发布后公网复核

- Web 提交 `e180782` 的 Workers Builds 和 quality 均成功。
- `ex.tokdou.com/account/kyc` 在 1440/390 像素真实登录会话中显示服务器持久化的 VERIFIED；KYC 状态、材料列表、服务商三个接口均返回 200。
- 测试会话已调用 logout（200），本地和服务器临时会话文件已删除；本轮 Vite 进程已停止。仅保留部署回滚包及验证材料，没有启动临时交易集群。
