FROM openjdk:17-jdk as build
WORKDIR /workspace/app

RUN microdnf install git

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
#validate step will execute the scm plugin to perform checkout and installation of the pagopa-commons library
RUN ./mvnw validate -DskipTests
RUN ./mvnw dependency:copy-dependencies
RUN ./mvnw dependency:go-offline

COPY src src
COPY api-spec api-spec
COPY eclipse-style.xml eclipse-style.xml
RUN ./mvnw install -DskipTests --offline
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM openjdk:17-slim

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted
#AI Agent
ADD --chown=user https://github.com/microsoft/ApplicationInsights-Java/releases/download/3.4.1/applicationinsights-agent-3.4.1.jar ./applicationinsights-agent.jar
COPY --chown=user applicationinsights.json ./applicationinsights.json
#ELK Agent
ADD --chown=user https://search.maven.org/remotecontent?filepath=co/elastic/apm/elastic-apm-agent/1.38.0/elastic-apm-agent-1.38.0.jar ./apm-elk-agent.jar

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/application/ ./
RUN true

ENTRYPOINT ["java","-javaagent:apm-elk-agent.jar","-javaagent:applicationinsights-agent.jar","--enable-preview","org.springframework.boot.loader.JarLauncher"]
#ENTRYPOINT ["java","--enable-preview","org.springframework.boot.loader.JarLauncher"]
