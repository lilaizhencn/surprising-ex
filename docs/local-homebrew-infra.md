# Local Homebrew Middleware

本地开发和全链路测试默认使用 Homebrew 安装的 PostgreSQL、Kafka、Redis，不再启动独立 Docker 中间件实例。

## Ports

- PostgreSQL: `localhost:5432`
- Kafka: `localhost:9092`
- Redis: `localhost:6379`
- Test websocket-provider: `localhost:9097` by default via `WEBSOCKET_PORT`

Homebrew Kafka keeps the client listener on `9092`; its local controller listener may occupy `9093`, so the trading test scripts default websocket-provider to `9097` instead of reusing `9093`.

## Service Commands

```bash
brew services start postgresql@18
brew services start kafka
brew services start redis
brew services list | rg 'postgresql|kafka|redis'
```

`brew services start` 会注册用户级 LaunchAgent，登录后自动启动。需要重载配置时使用：

```bash
brew services restart postgresql@18
brew services restart kafka
brew services restart redis
```

## Database

```bash
psql -d postgres -v ON_ERROR_STOP=1 -c "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'surprising') THEN CREATE ROLE surprising LOGIN PASSWORD 'surprising' CREATEDB; ELSE ALTER ROLE surprising WITH LOGIN PASSWORD 'surprising' CREATEDB; END IF; END \$\$;"
createdb -O surprising surprising_exchange
psql postgresql://surprising:surprising@localhost:5432/surprising_exchange -f init.sql
```

如果库已经存在，跳过 `createdb` 即可。

## Kafka Topics

```bash
./scripts/create-topics.sh
kafka-topics --bootstrap-server localhost:9092 --list | rg '^surprising'
```

`scripts/create-topics.sh` 会优先使用 Homebrew 的 `kafka-topics`，不需要 Docker wrapper。

## Test Scripts

这些脚本默认使用本机中间件，`START_INFRA` 默认是 `false`：

```bash
./scripts/kafka-trading-smoke.sh
PAIR_COUNT=50 LOAD_CONCURRENCY=16 ./scripts/kafka-trading-load-smoke.sh
./scripts/full-stack-real-config-smoke.sh
./scripts/market-maker-stress.sh
```

本地模式下 `RESET_KAFKA_MODE=recreate` 不会删除 Docker Kafka 容器或 volume，只会删除并重建项目 topic。历史 Docker 模式只保留给显式调试：`INFRA_MODE=docker START_INFRA=true`。

## Tuned Local Settings

PostgreSQL 使用 16GB 机器的本地压测参数：`max_connections=300`、`shared_buffers=2GB`、`work_mem=16MB`、`maintenance_work_mem=512MB`、`max_wal_size=8GB`、`checkpoint_timeout=15min`、`effective_cache_size=10GB`、`jit=off`。资金正确性测试保留 `fsync=on`、`full_page_writes=on`、`synchronous_commit=on`。

Kafka 保持标准 `9092` 端口，单机 broker/controller 监听 loopback，网络线程和 I/O 线程分别提高到 `8` 和 `16`，默认 topic 分区为 `8`，项目 topic 仍由 `scripts/create-topics.sh` 按脚本参数创建。

Redis 只监听 loopback，关闭 RDB/AOF 测试持久化，`maxmemory=2gb`、`maxmemory-policy=noeviction`、`io-threads=4`、`hz=50`。Redis 在本项目本地测试中不是资金事实源。
