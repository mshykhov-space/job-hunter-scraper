FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
ARG APP_VERSION=0.1.0
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon -PappVersion=$APP_VERSION

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S -g 1000 app && adduser -S -u 1000 app -G app
USER app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
