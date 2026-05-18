# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
COPY app/pom.xml app/
COPY module-common/pom.xml module-common/
COPY module-config/pom.xml module-config/
COPY module-session/pom.xml module-session/
COPY module-model/pom.xml module-model/
COPY module-tool/pom.xml module-tool/
COPY module-agent/pom.xml module-agent/
COPY module-web/pom.xml module-web/
COPY module-skill/pom.xml module-skill/
COPY module-knowledge/pom.xml module-knowledge/
COPY module-starter/pom.xml module-starter/
COPY module-starter/dear-module-spring-boot-autoconfigure/pom.xml module-starter/dear-module-spring-boot-autoconfigure/
COPY module-starter/dear-module-spring-boot-starter/pom.xml module-starter/dear-module-spring-boot-starter/
COPY module-prompt/pom.xml module-prompt/
COPY module-core/pom.xml module-core/
COPY . .
RUN mvn clean package -DskipTests -pl app -am

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /build/app/target/app-*.jar app.jar
EXPOSE 520
ENTRYPOINT ["java", "-jar", "app.jar"]
