# Stage 1: build the core-mock boot jar (context = repository root)
FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
COPY shared ./shared
COPY core-mock ./core-mock
RUN gradle :core-mock:assemble --no-daemon -q -Dorg.gradle.jvmargs=-Xmx768m

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/core-mock/build/libs/core-mock.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/app.jar"]
