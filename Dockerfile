FROM openjdk:17-slim as build
WORKDIR /workspace/app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:copy-dependencies
RUN ./mvnw dependency:go-offline

COPY src src
COPY api-spec api-spec
COPY nodo-wsdl nodo-wsdl
RUN ./mvnw install -DskipTests --offline
RUN mkdir target/extracted && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM openjdk:17-slim

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/target/extracted

ADD https://github.com/microsoft/ApplicationInsights-Java/releases/download/3.4.1/applicationinsights-agent-3.4.1.jar /applicationinsights-agent.jar

COPY --from=build --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=build --chown=user ${EXTRACTED}/application/ ./
RUN true

ENTRYPOINT ["java", "--enable-preview", "org.springframework.boot.loader.JarLauncher","-javaagent:applicationinsights-agent.jar"]
