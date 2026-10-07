FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY backend backend
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true -pl backend/bootstrap -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/bootstrap/target/intelhub-bootstrap-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
