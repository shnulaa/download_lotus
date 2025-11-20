# 第一阶段：构建
FROM maven:3.8.6-openjdk-8-slim AS build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# 第二阶段：运行（体积小 + 解决SSL问题）
FROM eclipse-temurin:8-jre

WORKDIR /app

ENV TZ=Asia/Shanghai

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /app/db /app/Temp && \
    chmod 755 /app/db /app/Temp

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
