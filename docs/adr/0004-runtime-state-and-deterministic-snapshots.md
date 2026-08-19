# Runtime State 与确定性快照分层

状态模型采用两层结构：Product Core owner 线程使用 exchange-core 风格的 primitive/hash Runtime State 执行余额、冻结、订单和持仓热路径；Cluster 快照、恢复、状态 hash 和审计使用按固定 ID 顺序生成的不可变 Snapshot State。Runtime State 不向异步线程暴露可变引用，所有 Reservation 变更仍由 Product Core 单线程原子裁决。

## Considered Options

- 继续在每笔命令中复制嵌套 `PersistentTreeMap`：保留快照便利性，但当前下单路径存在多层 AVL 查找、路径复制和大量对象分配，不能满足容量目标。
- 全部替换为普通 `HashMap`：查找更快，但破坏 owner 边界、遍历确定性和恢复 hash，不接受。
- 采用 Runtime State / Snapshot State 分层：保留资金安全与恢复契约，同时把 O(1) 平均查找和原地更新限制在单线程热路径，作为本项目方案。

## Consequences

Runtime State 必须维护 changed-key 日志并由 Snapshot Builder 完成稳定排序；每个产品线先迁移永续 `PLACE_ORDER`，通过资金守恒、重复命令、快照恢复和双模型 hash 对照后，才能迁移撤单、成交和其他产品线。
