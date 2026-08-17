# W4 执行记录：Core 基线与真实门禁

日期：2026-08-17  
分支：`codex/aeron-unified-core`  
基线 SHA：`f2dc62a5c745cf59ed445c73a1d7e3e95e756a94`

## 已执行

按约定逐条产品线、单集群运行：

```bash
for line in SPOT LINEAR_PERPETUAL INVERSE_PERPETUAL \
  LINEAR_DELIVERY INVERSE_DELIVERY OPTION; do
  PRODUCT_LINE="$line" FRESH=true KEEP_RUNTIME=false \
    bash scripts/product-line-api-flow-smoke.sh
done
```

结果：六条 Core-only 基线全部通过。

| ProductLine | 结果 | 观察值 |
| --- | --- | --- |
| `SPOT` | PASS | `spotMatchSmoke=PASS` |
| `LINEAR_PERPETUAL` | PASS | `derivativeSmoke=PASS`, `fundingNet=0` |
| `INVERSE_PERPETUAL` | PASS | `productLineGate=PASS`, `fundsDiff=0`, `bookLevels=0` |
| `LINEAR_DELIVERY` | PASS | `productLineGate=PASS`, `fundsDiff=0`, `bookLevels=0` |
| `INVERSE_DELIVERY` | PASS | `productLineGate=PASS`, `fundsDiff=0`, `bookLevels=0` |
| `OPTION` | PASS | `productLineGate=PASS`, `fundsDiff=0`, `bookLevels=0` |

使用 JDK 25 构建了隔离 W4 运行器：

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

静态 W4 检查结果：

```text
W4_STATIC_PREP=PASS manifests=6 order=required wallet=absent maker=required faults=not-exercised checker=fail-closed
```

## 真实门禁结果

未生成 `REAL_PASS`。隔离分支 `origin/codex/w3w5-t16-real-w4` 的真实运行器在启动第一条 `SPOT` 线时先被运行时编排拒绝：`run.sh line-up` 仍硬编码只接受 `LINEAR_PERPETUAL`，且该隔离 worktree 没有 exporter 构建产物。即使补齐运行时产物，`W4LifecycleQaMain.requireProviderCapabilities()` 仍固定 fail-closed：

```text
W4_REAL_CAPABILITY_PENDING
missing=provider-to-core-lifecycle,cursor-repeat-gap,pg-selected,maker-user-treasury-reconciliation
```

当前 `providerBoundaryObserved` 没有任何真实赋值路径，因此不能把 Core 直连命令误称为 Provider API 验证，也不能生成 `maker=OBSERVED` 或 `fundsReconciliation=OBSERVED`。

## 阶段结论

W4 继续保持 `PARTIAL`。Core 的六条产品线基线和静态配置门禁已完成；真实出口仍缺：

1. 通过实际 Order/Trigger/Account/Risk/Funding/Liquidation Provider API 驱动同一 Aeron Core。
2. 交割/期权生命周期必须从对应 Provider 入口执行，而不是直接构造 Core 命令。
3. 记录 cursor 重复、缺口、重启续跑结果。
4. 对 maker、用户和 Treasury 做 Provider 视角的资金守恒对账，差异必须为零。

在上述能力接通前不合并 `w3w5-t16-real-w4`，不删除旧链路，也不宣称 W4 完成。
