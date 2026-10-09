# 公告中心

公告由 Gateway 独立的 `announcement` package 提供，运营人员在 `surprising-admin-web` 创建与维护，交易用户 Web 和 Flutter 客户端读取同一套客户端 API。公告内容与受众配置保存在 Gateway PostgreSQL，不进入撮合、账户或交易事件链路。

## 业务流程

1. 管理端创建草稿，填写中英文内容、类别、优先级、生效时间、可选失效时间、产品线、平台和展示位置。
2. Gateway 校验字符长度、时间顺序、目标枚举和翻译 locale；编辑使用版本号做乐观并发控制，并在编辑后清除该公告的已读状态。
3. 发布前必须具备完整的 `zh-CN` 与 `en-US` 标题和正文，并包含公告中心位置。立即发布与定时发布使用同一状态；服务端按时间判断公告是否当前可见。
4. 发布和撤回通过后台高风险操作审批链路；状态变化、操作者、原因和当时的完整公告快照写入审计表。撤回必须填写原因。
5. 客户端按 locale、产品线、平台和展示位置读取可见公告。用户可逐条或按当前筛选范围全部标为已读；匿名用户仍可读取公告，但不会得到个性化已读状态。

## Gateway API

管理接口都位于 `/api/v1/admin/announcements`，需要管理员权限：

| 方法 | 路径 | 权限/用途 |
| --- | --- | --- |
| GET | `/` | `admin.announcements.read`，分页、状态筛选和全文搜索 |
| GET | `/{id}` | `admin.announcements.read`，读取公告及翻译 |
| GET | `/{id}/audit` | `admin.announcements.read`，读取最近 200 条变更快照 |
| POST | `/` | `admin.announcements.write`，创建草稿 |
| PUT | `/{id}` | `admin.announcements.write`，按版本更新草稿或已发布内容 |
| POST | `/{id}/publish` | `admin.announcements.publish`，发布或定时发布；需要审批 |
| POST | `/{id}/withdraw` | `admin.announcements.publish`，撤回并记录原因；需要审批 |

客户端接口位于 `/api/v1/announcements`：列表参数 `locale`、`productLine`、`platform`、`placement`、`offset` 和 `limit`；另外提供未读数、单条详情、单条已读与当前筛选范围全部已读接口。详情与列表使用 locale 回退：精确 locale、基础语言、`en-US`、`zh-CN`。

产品线值为 `SPOT`、`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION`；也可选 `ALL`。平台为 `WEB`、`IOS`、`ANDROID`；展示位置为 `CENTER`、`MODAL`、`BANNER`。发布公告必须包含 `CENTER`，可同时增加弹窗或横幅位置。

## 数据库部署

已有环境先执行 `deployment/migrations/20261005-announcement-center.sql`。新环境的 `init.sql` 已包含相同表结构和权限种子。迁移创建公告、翻译、受众目标、用户已读和操作审计表，并向 `ADMIN` 角色授予公告读取、草稿编辑和发布权限；其他角色需通过权限管理按职责分配。

表结构和权限由迁移维护；客户端只通过 API 读写，不直接访问这些表。公告快照删除依赖父公告外键级联，因此业务端不提供物理删除操作。
