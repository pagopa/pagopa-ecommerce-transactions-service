FROM openjdk:17-jdk-alpine as build
WORKDIR /workspace/app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:copy-dependencies
RUN ./mvnw dependency:go-offline

COPY src src
RUN ./mvnw install -DskipTests --offline
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM openjdk:17-jdk-alpine

RUN addgroup -S user && adduser -S user -G user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=user ${EXTRACTED}/application/ ./

ENTRYPOINT ["java","org.springframework.boot.loader.JarLauncher"]
