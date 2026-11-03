# Aeron Operations Tools

Cluster probes, diagnostic entry points, and offline/replay utilities. These tools are separate from the trading hot path. Check each main class and argument parser before running a state-changing operation.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-aeron-core/surprising-aeron-tools -am test
```

## Source navigation

- [Main java](src/main/java)
- [Main resources](src/main/resources)
- [Tests](src/test)
