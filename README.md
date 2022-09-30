# pagopa-ecommerce-transactions-service

## What is this?

This is a PagoPA microservice that handles transactions' lifecycle and workflow.

### Environment variables

These are all environment variables needed by the application:

| Variable name                                  | Description                                                                        | type   | default |
|------------------------------------------------|------------------------------------------------------------------------------------|--------|---------|
| MONGO_HOST                                     | Host where MongoDB instance used to persise events and view resides                | string |
| MONGO_USERNAME                                 | Username used for connecting to MongoDB instance                                   | string |         |
| MONGO_PASSWORD                                 | Password used for connecting to MongoDB instance                                   | string |         |
| REDIS_HOST                                     | Host where the redis instance used to persist idempotency keys can be found        | string |         |
| REDIS_PASSWORD                                 | Password used for connecting to Redis instance                                     | string |         |
| ECOMMERCE_SESSIONS_URI                         | eCommerce Sessions service connection URI                                          | string |         |
| ECOMMERCE_SESSIONS_READ_TIMEOUT                | Timeout for requests towards eCommerce Sessions service                            | number |         |
| ECOMMERCE_SESSIONS_CONNECTION_TIMEOUT          | Timeout for establishing connections towards eCommerce Sessions service            | number |         |
| PAYMENT_TRANSACTION_GATEWAY_URI                | Payment transactions gateway service connection URI                                | string |         |
| PAYMENT_TRANSACTION_GATEWAY_READ_TIMEOUT       | Timeout for requests towards Payment transactions gateway service                  | number |         |
| PAYMENT_TRANSACTION_GATEWAY_CONNECTION_TIMEOUT | Timeout for establishing connections towards Payment transactions gateway service  | number |         |
| NODO_URI                                       | Nodo connection URI                                                                | string |         |
| NODO_READ_TIMEOUT                              | Timeout for requests towards Nodo                                                  | number |         |
| NODO_CONNECTION_TIMEOUT                        | Timeout for establishing connections towards Nodo                                  | number |         |
| PAYMENT_INSTRUMENTS_SERVICE_URI                | eCommerce payment instruments service connection URI                               | string |         |
| PAYMENT_INSTRUMENTS_SERVICE_READ_TIMEOUT       | Timeout for requests towards eCommerce payment instruments service                 | number |         |
| PAYMENT_INSTRUMENTS_SERVICE_CONNECTION_TIMEOUT | Timeout for establishing connections towards eCommerce payment instruments service | number |         |
| NOTIFICATIONS_SERVICE_URI                      | Notifications service connection URI                                               | string |         |
| NOTIFICATIONS_SERVICE_READ_TIMEOUT             | Timeout for requests towards Notifications service                                 | number |         |
| NOTIFICATIONS_SERVICE_CONNECTION_TIMEOUT       | Timeout for establishing connections towards Notifications service                 | number |         |
| NOTIFICATIONS_SERVICE_API_KEY                  | Notifications service API Key                                                      | string |         |
| NODOPERPM_URI                                  | NodoPerPM connection URI                                                           | string |         |
| NODOPERPM_READ_TIMEOUT                         | NodoPerPM read timeout                                                             | number |         |
| NODOPERPM_CONNECTION_TIMEOUT                   | NodoPerPM connection timeout                                                       | number |         |

An example configuration of these environment variables is in the `.env.example` file.

## Run the application with `Docker`

Create your environment typing :
```sh
cp .env.example .env
``` 

Then from current project directory run :
```sh
docker-compose up
```

if all right you'll see something like that :

```sh
# -- snip --
pagopa-ecommerce-transactions-service-mongo-1                          | {"t":{"$date":"2022-04-27T13:12:50.647+00:00"},"s":"I",  "c":"NETWORK",  "id":23015,   "ctx":"listener","msg":"Listening on","attr":{"address":"0.0.0.0"}}
pagopa-ecommerce-transactions-service-mongo-1                          | {"t":{"$date":"2022-04-27T13:12:50.647+00:00"},"s":"I",  "c":"NETWORK",  "id":23016,   "ctx":"listener","msg":"Waiting for connections","attr":{"port":27017,"ssl":"off"}}
## -- snip --
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |   .   ____          _            __ _ _
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |  /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | ( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |  \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |   '  |____| .__|_| |_|_| |_\__, | / / / /
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |  =========|_|==============|___/=/_/_/_/
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |  :: Spring Boot ::                (v2.6.7)
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  |
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:52.189  INFO 1 --- [           main] t.PagopaEcommerceTransactionsApplication : Starting PagopaEcommerceTransactionsApplication v0.0.1-SNAPSHOT using Java 18-ea on db36c3a40060 with PID 1 (/app/BOOT-INF/classes started by root in /app)
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:52.199  INFO 1 --- [           main] t.PagopaEcommerceTransactionsApplication : No active profile set, falling back to 1 default profile: "default"
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:54.492  INFO 1 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port(s): 8080 (http)
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:54.514  INFO 1 --- [           main] o.apache.catalina.core.StandardService   : Starting service [Tomcat]
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:54.515  INFO 1 --- [           main] org.apache.catalina.core.StandardEngine  : Starting Servlet engine: [Apache Tomcat/9.0.62]
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:54.699  INFO 1 --- [           main] o.a.c.c.C.[Tomcat].[localhost].[/]       : Initializing Spring embedded WebApplicationContext
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:54.699  INFO 1 --- [           main] w.s.c.ServletWebServerApplicationContext : Root WebApplicationContext: initialization completed in 2349 ms
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:55.784  INFO 1 --- [           main] c.a.c.i.jackson.JacksonVersion           : Package versions: jackson-annotations=2.13.2, jackson-core=2.13.2, jackson-databind=2.13.2.1, jackson-dataformat-xml=2.13.2, jackson-datatype-jsr310=2.13.2, azure-core=1.26.0, Troubleshooting version conflicts: https://aka.ms/azsdk/java/dependency/troubleshoot
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:55.924  INFO 1 --- [           main] AbstractAzureServiceClientBuilderFactory : Will configure the default credential of type DefaultAzureCredential for class com.azure.identity.DefaultAzureCredentialBuilder.
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:56.279  INFO 1 --- [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port(s): 8080 (http) with context path ''
pagopa-ecommerce-transactions-service-pagopa-ecommerce-transactions-1  | 2022-04-27 13:12:56.296  INFO 1 --- [           main] t.PagopaEcommerceTransactionsApplication : Started PagopaEcommerceTransactionsApplication in 5.104 seconds (JVM running for 6.584)
```

When running with the Docker container you can check data persisted to either Mongo or Redis with their respective web interfaces (Mongo express/Redis Insight). To do so, go to:
 * http://localhost:8001 for Redis Insight
 * http://localhost:8081 for Mongo Express

## Run the application with `springboot-plugin`

Create your environment:
```sh
export $(grep -v '^#' .env.local | xargs)
``` 

Then from current project directory run :
```sh
 mvn spring-boot:run
```
