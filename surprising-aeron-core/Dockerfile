FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 surprising \
    && useradd --system --uid 10001 --gid surprising --home-dir /var/lib/surprising --create-home surprising

WORKDIR /opt/surprising
COPY surprising-aeron-service/target/surprising-aeron-service.jar /opt/surprising/service.jar
COPY surprising-aeron-tools/target/surprising-aeron-tools.jar /opt/surprising/tools.jar

RUN mkdir -p /var/lib/surprising/aeron \
    && chown -R surprising:surprising /var/lib/surprising /opt/surprising

USER surprising
ENTRYPOINT ["java", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED"]
