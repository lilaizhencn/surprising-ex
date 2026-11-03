# Price Consumer

Reusable product-scoped price event consumption and cache integration. Host applications own listener lifecycle and resources; this module is not a standalone price provider.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-price/surprising-price-consumer -am test
```

## Source navigation

- [Main java](src/main/java)
- [Tests](src/test)
