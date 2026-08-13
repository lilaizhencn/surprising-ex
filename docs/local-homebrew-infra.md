# 本地 Homebrew 中间件

本地开发和全链路测试默认使用 Homebrew 安装的 PostgreSQL、Kafka，不再启动独立 Docker 中间件实例。

## 端口

- PostgreSQL：`localhost:5432`
- Kafka：`localhost:9092`
- 测试用 websocket-provider：默认通过 `WEBSOCKET_PORT` 使用 `localhost:9097`

Homebrew Kafka 的客户端监听端口为 `9092`，本地控制器可能占用 `9093`，因此交易测试脚本默认让
websocket-provider 使用 `9097`，不复用 `9093`。

## 服务命令

```bash
brew services start postgresql@18
brew services start kafka
brew services list | rg 'postgresql|kafka'
```

`brew services start` 会注册用户级 LaunchAgent，登录后自动启动。需要重载配置时使用：

```bash
brew services restart postgresql@18
brew services restart kafka
```

## 数据库

```bash
psql -d postgres -v ON_ERROR_STOP=1 -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'surprising') THEN CREATE ROLE surprising LOGIN PASSWORD 'surprising' CREATEDB; ELSE ALTER ROLE surprising WITH LOGIN PASSWORD 'surprising' CREATEDB; END IF; END \$\$;"
createdb -O surprising surprising_exchange
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

如果库已经存在，跳过 `createdb` 即可。

## Kafka 主题

```bash
./scripts/create-topics.sh
kafka-topics --bootstrap-server localhost:9092 --list | rg '^surprising'
```

`scripts/create-topics.sh` 会优先使用 Homebrew 的 `kafka-topics`，不需要 Docker 包装命令。

## 测试脚本

这些脚本默认使用本机中间件，`START_INFRA` 默认是 `false`：

```bash
./scripts/kafka-trading-smoke.sh
PRODUCT_LINES=LINEAR_PERPETUAL BUILD_SERVICES=auto CREATE_KAFKA_TOPICS=true KAFKA_INCLUDE_LEGACY_PERP_TOPICS=false KEEP_TMP=true ./scripts/product-line-api-flow-smoke.sh
```

本地模式下 `RESET_KAFKA=true` 只删除并重建项目 topic，不会删除本机 Kafka 数据目录。历史 Docker Compose 模式已经移除；需要临时容器调试时只保留脚本内的直接 Docker 模式。

## 本地调优参数

PostgreSQL 使用 16GB 机器的本地压测参数：`max_connections=300`、`shared_buffers=2GB`、`work_mem=16MB`、`maintenance_work_mem=512MB`、`max_wal_size=8GB`、`checkpoint_timeout=15min`、`effective_cache_size=10GB`、`jit=off`。资金正确性测试保留 `fsync=on`、`full_page_writes=on`、`synchronous_commit=on`。

Kafka 保持标准 `9092` 端口，单机 Broker/Controller 监听本机回环地址，网络线程和 I/O 线程分别
提高到 `8` 和 `16`；单节点测试默认 Topic 分区为 `4`，项目 Topic 仍由 `scripts/create-topics.sh`
按脚本参数创建。
