FROM eclipse-temurin:21-jdk-alpine@sha256:2f2f553ce09d25e2d2f0f521ab94cd73f70c9b21327a29149c23a2b63b8e29a0 AS build
WORKDIR /workspace/app

RUN apk add --no-cache git

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

#validate step will execute the scm plugin to perform checkout and installation of the pagopa-commons library
RUN ./mvnw validate -DskipTests -Dmaven.site.skip=true
RUN ./mvnw dependency:copy-dependencies
RUN ./mvnw dependency:go-offline

COPY src src
COPY api-spec api-spec
COPY eclipse-style.xml eclipse-style.xml
RUN ./mvnw compile spring-boot:process-aot install -DskipTests --offline

FROM eclipse-temurin:21-jre-alpine@sha256:8728e354e012e18310faa7f364d00185277dec741f4f6d593af6c61fc0eb15fd AS optimizer

WORKDIR /workspace/app

#copy maven target folder from previous build
COPY --from=build /workspace/app/target target

COPY src/test/resources/application-tests.properties application-tests.properties
# extract jar
RUN mkdir extracted && java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# generate Class Data Sharing archive
WORKDIR /workspace/app/cds

RUN if [ -z "$(ls -A ../extracted/dependencies)" ];\
then echo "Skipped empty folder"; \
else cp -R ../extracted/dependencies/* ./; \
fi
RUN if [ -z "$(ls -A ../extracted/spring-boot-loader)" ];\
then echo "Skipped empty folder"; \
else cp -R ../extracted/spring-boot-loader/* ./; \
fi
RUN if [ -z "$(ls -A ../extracted/snapshot-dependencies)" ]; \
then echo "Skipped empty folder"; \
else cp -R ../extracted/snapshot-dependencies/* ./; \
fi
RUN if [ -z "$(ls -A ../extracted/application)" ]; \
then echo "Skipped empty folder"; \
else cp -R ../extracted/application/* ./; \
fi

RUN java \
-Dspring.aot.enabled=true \
-XX:ArchiveClassesAtExit=../cds.jsa \
-Dspring.context.exit=onRefresh \
-Dspring.config.location=/workspace/app/application-tests.properties \
org.springframework.boot.loader.launch.JarLauncher

FROM eclipse-temurin:21-jre-alpine@sha256:8728e354e012e18310faa7f364d00185277dec741f4f6d593af6c61fc0eb15fd

RUN addgroup --system user && adduser --ingroup user --system user
USER user:user

WORKDIR /app/

ARG EXTRACTED=/workspace/app/extracted
#ELK Agent
ADD --chown=user https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.1.0/opentelemetry-javaagent.jar .

COPY --from=optimizer --chown=user ${EXTRACTED}/dependencies/ ./
RUN true
COPY --from=optimizer --chown=user ${EXTRACTED}/spring-boot-loader/ ./
RUN true
COPY --from=optimizer --chown=user ${EXTRACTED}/snapshot-dependencies/ ./
RUN true
COPY --from=optimizer --chown=user ${EXTRACTED}/application/ ./
RUN true
COPY --from=optimizer --chown=user /workspace/app/cds.jsa cds.jsa
RUN true

ENTRYPOINT ["java", \
    "-javaagent:opentelemetry-javaagent.jar", \
    "-Dspring.aot.enabled=true", \
    "-XX:SharedArchiveFile=cds.jsa", \
    "org.springframework.boot.loader.launch.JarLauncher"\
    ]
