FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -ntp -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 voltmate
COPY --from=build /app/target/backend-0.1.0.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
