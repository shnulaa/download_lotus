# 第一阶段：构建 (使用 Maven 和 JDK 8)
FROM maven:3.8.6-openjdk-8-slim AS build

WORKDIR /app

# 先复制 pom.xml 以利用 Docker 缓存
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 复制源代码
COPY src ./src

# 构建应用（跳过测试以加快构建速度）
RUN mvn clean package -DskipTests

# 第二阶段：运行 (使用极小的 Alpine JRE)
FROM eclipse-temurin:8-jre-alpine

WORKDIR /app

# 设置时区（可选，方便查看日志时间）
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 从构建阶段复制 jar 包
COPY --from=build /app/target/*.jar app.jar

# 暴露端口
EXPOSE 8080

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]
