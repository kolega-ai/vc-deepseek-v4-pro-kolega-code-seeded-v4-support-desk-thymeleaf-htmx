# Stage 1: build the channel boot jar (context = repository root)
FROM gradle:8.10-jdk21 AS build
WORKDIR /src
COPY settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
COPY shared ./shared
COPY channel ./channel
RUN gradle :channel:assemble --no-daemon -q -Dorg.gradle.jvmargs=-Xmx768m

# Stage 2: runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/channel/build/libs/channel.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
