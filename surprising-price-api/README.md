# Price API

Shared price/FX/mark query contracts and model definitions. This library has no application process and does not own price-source connections or trading state.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. Do not infer standalone deployment or independent state authority from a Maven module boundary.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-price/surprising-price-api -am test
```

## Source navigation

- [Main java](src/main/java)
