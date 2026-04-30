FROM gradle:8-jdk17 AS build

WORKDIR /workspace
COPY settings.gradle build.gradle gradlew gradlew.bat ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/openchat.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/openchat.jar"]
