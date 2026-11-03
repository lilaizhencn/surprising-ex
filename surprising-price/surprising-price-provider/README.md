# Price Provider

Runnable index, mark, FX, and option reference quote application. Entry point: `com.surprising.price.SurprisingPriceApplication`.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-price/surprising-price-provider -am test
```

## Source navigation

- [Main java](src/main/java)
- [Main resources](src/main/resources)
- [Tests](src/test)
