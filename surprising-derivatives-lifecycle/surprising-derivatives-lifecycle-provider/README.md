# Derivatives Lifecycle Provider

Runnable lifecycle/funding application. Entry point: `com.surprising.derivatives.lifecycle.SurprisingDerivativesLifecycleApplication`. Core owns the resulting funds and position transitions.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider -am test
```

## Source navigation

- [Main java](src/main/java)
- [Main resources](src/main/resources)
- [Tests](src/test)
