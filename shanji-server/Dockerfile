# 闪记后端：多阶段构建（maven 构建 → JRE 运行）
FROM maven:3.9-eclipse-temurin-21 AS build
# Maven 走 Wagon/HttpClient 传输层，不认 -Dhttps.proxyHost 这类 JVM 系统属性，
# 只认 settings.xml 里的 <proxies>（host.docker.internal:8888 = 宿主代理，构建前需开着）
COPY maven-proxy-settings.xml /root/.m2/settings.xml
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
