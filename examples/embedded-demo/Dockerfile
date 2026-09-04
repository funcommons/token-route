# 嵌入式最小宿主示例（compose profile demo 用；构建上下文 = 仓库根）
# 基础镜像走 ARG（对齐根 Dockerfile：daocloud 源可覆盖）
ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
# 先装 starter 到本地仓库（demo 独立 pom 按本地坐标引用）
COPY pom.xml .
COPY token-route-starter/pom.xml token-route-starter/pom.xml
RUN mvn -q -pl token-route-starter -am dependency:go-offline || true
COPY token-route-starter/src token-route-starter/src
RUN mvn -q -pl token-route-starter -am install -DskipTests
# 再打 demo fat jar
COPY examples/embedded-demo examples/embedded-demo
RUN mvn -q -f examples/embedded-demo/pom.xml package -DskipTests

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=build /build/examples/embedded-demo/target/embedded-demo-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
