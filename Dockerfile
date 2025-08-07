FROM eclipse-temurin:21-jdk-alpine@sha256:2f2f553ce09d25e2d2f0f521ab94cd73f70c9b21327a29149c23a2b63b8e29a0 AS build
WORKDIR /workspace/app

RUN apk add --no-cache git

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN --mount=type=secret,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    ./mvnw dependency:copy-dependencies
RUN --mount=type=secret,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    ./mvnw dependency:go-offline

COPY src src
COPY api-spec api-spec
COPY eclipse-style.xml eclipse-style.xml
RUN --mount=type=secret,id=GITHUB_TOKEN,env=GITHUB_TOKEN \
    ./mvnw install -DskipTests --offline
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM eclipse-temurin:21-jre-alpine@sha256:8728e354e012e18310faa7f364d00185277dec741f4f6d593af6c61fc0eb15fd

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted
#ELK Agent
ADD --chown=user https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.1.0/opentelemetry-javaagent.jar .

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/application/ ./
RUN true

ENTRYPOINT ["java","-javaagent:opentelemetry-javaagent.jar","org.springframework.boot.loader.launch.JarLauncher"]
