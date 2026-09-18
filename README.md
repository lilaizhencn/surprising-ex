# Surprising 交易系统

## 项目介绍

Surprising 是一个正在开发和验证中的多产品线交易系统。本仓库承载交易后端，围绕交易撮合、账户结算、风险处理、行情分发和运营管理组织服务，并通过 Aeron Cluster 构建交易核心的高可用运行环境。

项目面向普通用户、做市商和运营人员。建设目标是在资金与持仓正确、业务可恢复的前提下，提高持续交易处理能力，并逐步完善前后端、监控、安全及部署体系。已有功能仍需持续联调和验收，后续计划不代表已经具备完整的生产运营能力。

### 产品线

| 产品线 | 业务范围 |
| --- | --- |
| 现货 | 下单、撤单、撮合、资产冻结与解冻、成交结算 |
| U 本位永续 | 保证金、持仓、资金费、风险检查与强平 |
| 币本位永续 | 反向合约计价、保证金、资金费与风险处理 |
| U 本位交割 | 保证金交易、到期交割与持仓结算 |
| 币本位交割 | 反向合约交易、到期交割与持仓结算 |
| 期权 | 权利金、持仓风险、到期行权与失效处理 |

六条产品线通过统一的产品线标识进行路由，分别隔离交易状态、账户语义、行情订阅和事件。各产品线使用独立的逻辑交易集群，不能混用币对、资金模型或结算规则。

## 总体架构

交易核心的 Owner 流水线位于 `surprising-aeron-core/surprising-aeron-service`：
`TradingCoreOwner` 每轮按需收集 Matcher/Lane 完成结果，再按 FIFO 连续退休 ready 命令，
不等待凑批；新准入或未 ready 的队首会重新推进异步工作。每条命令独立保留日志时间、位置、
资金校验、发布与响应边界。`OwnerCommandPipelineState` 直接引用窗口队首及实际依赖末项，
不复制命令序号、不维护单元素“批量前缀”；依赖项退休时清除引用，再复用槽位。

系统分为接入与业务服务、交易核心、可靠事件处理、实时推送与查询四个部分。图中的交易集群代表一条产品线，其他产品线按相同边界独立部署。

```mermaid
flowchart TB
    USER[用户前端与做市程序] --> GATE[接入网关：认证、接口与连接管理]
    ADMIN[后台管理前端] --> GATE
    GATE --> BIZ[业务服务：交易、账户、币对、资金费与衍生品生命周期]
    BIZ --> CLIENT[Aeron 客户端：按产品线路由命令]

    subgraph CLUSTER[单产品线三节点交易集群]
        LEADER[主节点：集群服务与交易核心]
        FOLLOWER1[从节点一：集群服务与交易核心]
        FOLLOWER2[从节点二：集群服务与交易核心]
        LEADER -->|日志复制| FOLLOWER1
        LEADER -->|日志复制| FOLLOWER2
        ARCHIVE[各节点的日志归档与快照]
        LEADER --- ARCHIVE
        FOLLOWER1 --- ARCHIVE
        FOLLOWER2 --- ARCHIVE
    end

    CLIENT --> LEADER
    LEADER -->|命令处理结果| CLIENT
    ARCHIVE --> EXPORT[独立可靠事件导出]
    EXPORT --> KAFKA[Kafka 可靠事件通道]
    KAFKA --> CONSUMER[业务事件消费、历史记录与对账]
    CONSUMER --> DB[(PostgreSQL：配置与业务持久化)]
    BIZ --> DB

    LEADER -->|提交后的有界实时出口| ROUTER[实时路由服务]
    PRICE[行情与价格服务] -->|实时价格与行情更新| ROUTER
    ROUTER --> VIEW[(Valkey：可重建查询视图与订阅路由)]
    BIZ -->|用户最新状态查询| VIEW
    ROUTER -->|Aeron 定向发送| WS[持有目标订阅的 WebSocket 节点]
    WS -->|公共行情与私有状态| USER
```

## 仓库模块

| 模块 | 主要职责 |
| --- | --- |
| [surprising-parent](surprising-parent/) | 构建、依赖与公共插件配置 |
| [surprising-product-api](surprising-product-api/) | 产品线定义与共享产品契约 |
| [surprising-aeron-core](surprising-aeron-core/) | 核心协议、集群服务、客户端、运维工具及独立测试压测模块 |
| [surprising-instrument](surprising-instrument/) | 币对与合约配置、交易状态管理 |
| [surprising-trading](surprising-trading/) | 订单、触发单与交易业务接入 |
| [surprising-account](surprising-account/) | 账户接口、资产与持仓查询及相关事件处理 |
| [surprising-market-data](surprising-market-data/) | 盘口、成交行情与市场数据服务 |
| [surprising-price](surprising-price/) | 指数价格、标记价格及价格分发 |
| [surprising-funding](surprising-funding/) | 永续资金费业务 |
| [surprising-derivatives-lifecycle](surprising-derivatives-lifecycle/) | 衍生品风险、强平、保险及交割行权相关业务 |
| [surprising-realtime](surprising-realtime/) | 实时路由、订阅目录、状态快照与 Valkey 查询视图 |
| [surprising-gateway](surprising-gateway/) | 接入、认证、管理接口与 WebSocket 连接 |
| [surprising-maker](surprising-maker/) | 做市程序与相关业务支持 |

用户前端项目为 `surprising-ex-web`、`surprising-client`，后台管理前端项目为 `surprising-admin-web`，与本仓库分别维护。
