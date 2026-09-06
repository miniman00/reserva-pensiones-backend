# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace

# Descarga dependencias en una capa separada para aprovechar la cache de Docker.
COPY pom.xml ./
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY src ./src
# La imagen solo se genera si compila y pasa los tests Maven.
RUN mvn -B -ntp clean verify

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Java 17 reconoce los limites de memoria del contenedor. Dejamos margen para
# memoria nativa, threads y buffers de Spring/Tomcat.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

COPY --from=build /workspace/target/pensions-api-*.jar /app/app.jar

# Render inyecta PORT en runtime (10000 por defecto). EXPOSE es documental.
EXPOSE 10000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
