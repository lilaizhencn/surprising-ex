# Build Parent

Central dependency versions, plugin versions, compiler settings, and build constraints. This is a parent POM, not a deployable process.

## Responsibilities and operation

See the [module-group guide](../README.md) for architecture, ownership boundaries, configuration, and operational requirements. All services use HotSpot JDK 27. Update shared versions here rather than overriding unrelated modules independently. Changes can affect the entire reactor.

## Build and verification

Run from the repository root:

```bash
mvn -pl surprising-parent -am validate
```
