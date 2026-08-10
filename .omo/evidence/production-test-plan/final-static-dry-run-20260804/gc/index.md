# JVM GC matrix

product_line=LINEAR_PERPETUAL
profile=local-low
cpu=16 memory_mb=16384
gc_values=g1 zgc repeats=1 execute=false

| gc | repeat | mode | result | evidence |
|---|---:|---|---|---|
| g1 | 1 | smoke-only | PLANNED_ONLY | .omo/evidence/production-test-plan/final-static-dry-run-20260804/gc/LINEAR_PERPETUAL-g1-r1 |
| zgc | 1 | smoke-only | PLANNED_ONLY | .omo/evidence/production-test-plan/final-static-dry-run-20260804/gc/LINEAR_PERPETUAL-zgc-r1 |
