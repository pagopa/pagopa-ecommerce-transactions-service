# pagopa-ecommerce-transactions-service

## What is this?

This is a PagoPA microservice that handles transactions' lifecycle and workflow.

### Environment variables

These are all environment variables needed by the application:

| Variable name                                  |     | Description                                                                                                                           | type    | default |
|------------------------------------------------|-----|---------------------------------------------------------------------------------------------------------------------------------------|---------|---------|
| MONGO_HOST                                     |     | Host where MongoDB instance used to persise events and view resides                                                                   | string  |
| MONGO_USERNAME                                 |     | Username used for connecting to MongoDB instance                                                                                      | string  |         |
| MONGO_PASSWORD                                 |     | Password used for connecting to MongoDB instance                                                                                      | string  |         |
| REDIS_HOST                                     |     | Host where the redis instance used to persist idempotency keys can be found                                                           | string  |         |
| REDIS_PASSWORD                                 |     | Password used for connecting to Redis instance                                                                                        | string  |         |
| PAYMENT_TRANSACTION_GATEWAY_URI                |     | Payment transactions gateway service connection URI                                                                                   | string  |         |
| PAYMENT_TRANSACTION_GATEWAY_READ_TIMEOUT       |     | Timeout for requests towards Payment transactions gateway service                                                                     | number  |         |
| PAYMENT_TRANSACTION_GATEWAY_CONNECTION_TIMEOUT |     | Timeout for establishing connections towards Payment transactions gateway service                                                     | number  |         |
| NODO_URI                                       |     | Nodo connection URI                                                                                                                   | string  |         |
| NODO_READ_TIMEOUT                              |     | Timeout for requests towards Nodo                                                                                                     | number  |         |
| NODO_CONNECTION_TIMEOUT                        |     | Timeout for establishing connections towards Nodo                                                                                     | number  |         |
| NODO_ALL_CCP_ON_TRANSFER_IBAN_ENABLED          |     | Flag to enable light check for allCCP. If true it checks only all transfers' iban. If false, it checks also metadata                  | boolean |         |
| NODO_NODEFORPSP_API_KEY                        |     | API Key for NODE FOR PSP WS                                                                                                           | string  |         |
| NODO_CLOSEPAYMENT_API_KEY                      |     | API Key for Nodo closePayment API                                                                                                     | string  |         |
| ECOMMERCE_PAYMENT_METHODS_URI                  |     | eCommerce payment methods service connection URI                                                                                      | string  |         |
| ECOMMERCE_PAYMENT_METHODS_READ_TIMEOUT         |     | Timeout for requests towards eCommerce payment methods service                                                                        | number  |         |
| ECOMMERCE_PAYMENT_METHODS_CONNECTION_TIMEOUT   |     | Timeout for establishing connections towards eCommerce payment methods  service                                                       | number  |         |
| ECOMMERCE_PAYMENT_METHODS_APY_KEY              |     | Payment methods API key                                                                                                               | string  |         |
| NOTIFICATIONS_SERVICE_URI                      |     | Notifications service connection URI                                                                                                  | string  |         |
| NOTIFICATIONS_SERVICE_READ_TIMEOUT             |     | Timeout for requests towards Notifications service                                                                                    | number  |         |
| NOTIFICATIONS_SERVICE_CONNECTION_TIMEOUT       |     | Timeout for establishing connections towards Notifications service                                                                    | number  |         |
| NOTIFICATIONS_SERVICE_API_KEY                  |     | Notifications service API Key                                                                                                         | string  |         |
| PAYMENT_TOKEN_VALIDITY_TIME                    |     | Validity time in seconds of a payment token                                                                                           | number  |         |
| ECOMMERCE_STORAGE_TRANSIENT_CONNECTION_STRING  |     | Transient queue connection string                                                                                                     | string  |         |
| TRANSACTION_EXPIRATION_QUEUE_NAME              |     | Name of the queue for transaction expiration for activated transactions                                                               | string  |         |
| TRANSACTION_CLOSE_PAYMENT_RETRY_QUEUE_NAME     |     | Name of the retry queue for closure error events                                                                                      | string  |         |
| TRANSACTION_CLOSE_PAYMENT_QUEUE_NAME           |     | Name of the queue for close payment events                                                                                            | string  |         |
| TRANSACTION_NOTIFICATIONS_QUEUE_NAME           |     | Name of the queue for notification requested events                                                                                   | string  |         |
| TRANSACTION_REFUND_QUEUE_NAME                  |     | Name of the refund queue for transactions that receive a closePayment with OK authorization and KO outcome                            | string  |         |
| TRANSIENT_QUEUES_TTL_SECONDS                   |     | TTL to be used when sending events on transient queues                                                                                | number  | 7 days  |
| TRANSACTIONS_RETRY_OFFSET                      |     | Seconds to offset validity end to account for more retries                                                                            | number  |         |
| CLOSURE_RETRY_INTERVAL                         |     | Seconds to wait at closing the transaction before making a retry                                                                      | number  |         |
| PERSONAL_DATA_VAULT_API_KEY                    |     | API Key for Personal Data Vault (PDV is used to safely encrypt PIIs, e.g. the user's email address)                                   | string  |         |
| PERSONAL_DATA_VAULT_API_BASE_PATH              |     | API base path for Personal Data Vault                                                                                                 | string  |         |
| LOGO_CARD_BRANDING_MAPPING                     |     | Key-value string map that maps card brand to logo to be used into success mail                                                        | string  |         |
| NPG_API_KEY                                    |     | API Key for Nuovo Payment Gateway (NPG, used for authorizing payments).                                                               | string  |         |
| NPG_URI                                        |     | NPG connection uri                                                                                                                    | string  |         |
| NPG_READ_TIMEOUT                               |     | Timeout for requests towards NPG                                                                                                      | string  |         |
| NPG_CONNECTION_TIMEOUT                         |     | Timeout for establishing connections towards NPG                                                                                      | string  |         |
| NPG_CARDS_PSP_KEYS                             |     | Secret structure that holds psp - api keys association for authorization request                                                      | string  |         |
| NPG_CARDS_PSP_LIST                             |     | List of all psp ids that are expected to be found into the NPG_CARDS_PSP_KEYS configuration (used for configuration cross validation) | string  |         |
| CHECKOUT_BASE_PATH                             |     | Checkout basepath where the user will be brought to after the authorization process is completed                                      | string  |         |
| ECOMMERCE_EVENT_VERSION                        |     | Ecommerce event version used during transaction activated value accepted V1 and v2                                                    | string  |         |

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

When running with the Docker container you can check data persisted to either Mongo or Redis with their respective web
interfaces (Mongo express/Redis Insight). To do so, go to:

* http://localhost:8001 for Redis Insight
* http://localhost:8081 for Mongo Express

## Run the application with `springboot-plugin`

Create your environment:

```sh
export $(grep -v '^#' .env.local | xargs)
```

Then from current project directory run :

```sh
mvn validate # --> used to perform ecommerce-commons library checkout from git repo and install throught maven plugin
mvn spring-boot:run
```

For testing purpose the commons reference can be change from a specific release to a branch by changing the following
configurations tags:

FROM:

```sh
<scmVersionType>tag</scmVersionType>
<scmVersion>${pagopa-ecommerce-commons.version}</scmVersion>
```

TO:

```sh
<scmVersionType>branch</scmVersionType>
<scmVersion>name-of-a-specific-branch-to-link</scmVersion>
```

updating also the commons library version to the one of the specific branch

## Code formatting

Code formatting checks are automatically performed during build phase.
If the code is not well formatted an error is raised blocking the maven build.

Helpful commands:

```sh
mvn spotless:check # --> used to perform format checks
mvn spotless:apply # --> used to format all misformatted files
```

## CI

Repo has Github workflow and actions that trigger Azure devops deploy pipeline once a PR is merged on main branch.

In order to properly set version bump parameters for call Azure devops deploy pipelines will be check for the following
tags presence during PR analysis:

| Tag                | Semantic versioning scope | Meaning                                                           |
|--------------------|---------------------------|-------------------------------------------------------------------|
| patch              | Application version       | Patch-bump application version into pom.xml and Chart app version |
| minor              | Application version       | Minor-bump application version into pom.xml and Chart app version |
| major              | Application version       | Major-bump application version into pom.xml and Chart app version |
| ignore-for-release | Application version       | Ignore application version bump                                   |
| chart-patch        | Chart version             | Patch-bump Chart version                                          |
| chart-minor        | Chart version             | Minor-bump Chart version                                          |
| chart-major        | Chart version             | Major-bump Chart version                                          |
| skip-release       | Any                       | The release will be skipped altogether                            |

For the check to be successfully passed only one of the `Application version` labels and only ones of
the `Chart version` labels must be contemporary present for a given PR or the `skip-release` for skipping release step
