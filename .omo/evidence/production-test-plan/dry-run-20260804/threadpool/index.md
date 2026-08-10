# Tomcat/thread pool matrix

product_line=LINEAR_PERPETUAL
profile=local-low
cases=100:20:1000:2000 repeats=1 execute=false
threadpool_require_tomcat=false

| maxThreads | minSpare | acceptCount | maxConnections | repeat | result | evidence |
|---:|---:|---:|---:|---:|---|---|
| 100 | 20 | 1000 | 2000 | 1 | DRY_RUN | .omo/evidence/production-test-plan/dry-run-20260804/threadpool/tomcat-100-20-1000-2000-r1 |
