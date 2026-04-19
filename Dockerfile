FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/ask-the-repo/ ./
EXPOSE 3000
ENTRYPOINT ["./bin/ask-the-repo"]
CMD ["serve"]
