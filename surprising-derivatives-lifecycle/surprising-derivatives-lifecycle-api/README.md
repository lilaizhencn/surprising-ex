# Derivatives Lifecycle API

Shared risk, liquidation, insurance, ADL, and funding contracts. This library does not schedule tasks or adjudicate balances.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-api -am test
```

## Source navigation

- [Main java](src/main/java)
