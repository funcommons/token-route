# token-route（app + Redis，部署形态见 01_设计方案 §7.2）
# 基础镜像走 ARG，默认用 daocloud 镜像源（内网/受限网络）；公网环境可覆盖为 docker.io 官方源
ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17
ARG JRE_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre

# 构建层
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml .
COPY token-route-app/pom.xml token-route-app/pom.xml
COPY token-route-sdk/pom.xml token-route-sdk/pom.xml
# 依赖预取（jitpack/aliyun 失败不阻塞构建，后续 package 再补拉）
RUN mvn -q -pl token-route-app -am dependency:go-offline || true
COPY token-route-app/src token-route-app/src
COPY token-route-sdk/src token-route-sdk/src
RUN mvn -q -pl token-route-app -am package -DskipTests

# 运行层
FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=build /build/token-route-app/target/token-route-app-*.jar app.jar
EXPOSE 9302
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
