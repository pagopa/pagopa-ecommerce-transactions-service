# pagopa-ecommerce-transactions-service

## What is this?

This is a PagoPA microservice that handles eCommerce transactions' lifecycle and workflow.

## Requirements

- **Java 21** or higher
- **Maven 3.6+**
- **Docker** (for containerized deployment)

### Environment variables

These are all environment variables needed by the application:

| Variable name                                   |      | Description                                                                                                                                                                     | type    | default |
|-------------------------------------------------|------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------|---------|
| MONGO_HOST                                      |      | Host where MongoDB instance used to persise events and view resides                                                                                                             | string  |
| MONGO_USERNAME                                  |      | Username used for connecting to MongoDB instance                                                                                                                                | string  |         |
| MONGO_PASSWORD                                  |      | Password used for connecting to MongoDB instance                                                                                                                                | string  |         |
| MONGO_PORT                                      |      | Port used for connecting to MongoDB instance                                                                                                                                    | string  |         |
| MONGO_MIN_POOL_SIZE                             |      | Min amount of connections to be retained into connection pool. See docs *                                                                                                       | string  |         |
| MONGO_MAX_POOL_SIZE                             |      | Max amount of connections to be retained into connection pool.See docs *                                                                                                        | string  |         |
| MONGO_MAX_IDLE_TIMEOUT_MS                       |      | Max timeout after which an idle connection is killed in milliseconds. See docs *                                                                                                | string  |         |
| MONGO_CONNECTION_TIMEOUT_MS                     |      | Max time to wait for a connection to be opened. See docs *                                                                                                                      | string  |         |
| MONGO_SOCKET_TIMEOUT_MS                         |      | Max time to wait for a command send or receive before timing out. See docs *                                                                                                    | string  |         |
| MONGO_SERVER_SELECTION_TIMEOUT_MS               |      | Max time to wait for a server to be selected while performing a communication with Mongo in milliseconds. See docs *                                                            | string  |         |
| MONGO_WAITING_QUEUE_MS                          |      | Max time a thread has to wait for a connection to be available in milliseconds. See docs *                                                                                      | string  |         |
| MONGO_HEARTBEAT_FREQUENCY_MS                    |      | Hearth beat frequency in milliseconds. This is an hello command that is sent periodically on each active connection to perform an health check. See docs *                      | string  |         |
| REDIS_HOST                                      |      | Host where the redis instance used to persist idempotency keys can be found                                                                                                     | string  |         |
| REDIS_PASSWORD                                  |      | Password used for connecting to Redis instance                                                                                                                                  | string  |         |
| NODO_URI                                        |      | Nodo connection URI                                                                                                                                                             | string  |         |
| NODO_READ_TIMEOUT                               |      | Timeout for requests towards Nodo                                                                                                                                               | number  |         |
| NODO_CONNECTION_TIMEOUT                         |      | Timeout for establishing connections towards Nodo                                                                                                                               | number  |         |
| NODO_ALL_CCP_ON_TRANSFER_IBAN_ENABLED           |      | Flag to enable light check for allCCP. If true it checks only all transfers' iban. If false, it checks also metadata                                                            | boolean |         |
| NODE_FOR_PSP_URI                                |      | Node for psp api URI                                                                                                                                                            | string  |         |
| NODO_PER_PM_URI                                 |      | Nodo per PM api URI                                                                                                                                                             | string  |         |
| NODO_ECOMMERCE_CLIENT_ID                        |      | ecommerce client id used for node closePayment                                                                                                                                  | string  |         |
| ECOMMERCE_PAYMENT_METHODS_URI                   |      | eCommerce payment methods service connection URI                                                                                                                                | string  |         |
| ECOMMERCE_PAYMENT_METHODS_READ_TIMEOUT          |      | Timeout for requests towards eCommerce payment methods service                                                                                                                  | number  |         |
| ECOMMERCE_PAYMENT_METHODS_CONNECTION_TIMEOUT    |      | Timeout for establishing connections towards eCommerce payment methods  service                                                                                                 | number  |         |
| ECOMMERCE_PAYMENT_METHODS_APY_KEY               |      | Payment methods API key                                                                                                                                                         | string  |         |
| WALLET_URI                                      |      | pagopa wallet service connection URI                                                                                                                                            | string  |         |
| WALLET_READ_TIMEOUT                             |      | Timeout for requests towards wallet service                                                                                                                                     | number  |         |
| WALLET_CONNECTION_TIMEOUT                       |      | Timeout for establishing connections towards wallet service                                                                                                                     | number  |         |
| WALLET_API_KEY                                  |      | wallet service API key                                                                                                                                                          | string  |         |
| NOTIFICATIONS_SERVICE_URI                       |      | Notifications service connection URI                                                                                                                                            | string  |         |
| NOTIFICATIONS_SERVICE_READ_TIMEOUT              |      | Timeout for requests towards Notifications service                                                                                                                              | number  |         |
| NOTIFICATIONS_SERVICE_CONNECTION_TIMEOUT        |      | Timeout for establishing connections towards Notifications service                                                                                                              | number  |         |
| NOTIFICATIONS_SERVICE_API_KEY                   |      | Notifications service API Key                                                                                                                                                   | string  |         |
| PAYMENT_TOKEN_VALIDITY_TIME                     |      | Validity time in seconds of a payment token                                                                                                                                     | number  |         |
| ECOMMERCE_STORAGE_TRANSIENT_CONNECTION_STRING   |      | Transient queue connection string                                                                                                                                               | string  |         |
| TRANSACTION_EXPIRATION_QUEUE_NAME               |      | Name of the queue for transaction expiration for activated transactions                                                                                                         | string  |         |
| TRANSACTION_CLOSE_PAYMENT_RETRY_QUEUE_NAME      |      | Name of the retry queue for closure error events                                                                                                                                | string  |         |
| TRANSACTION_CLOSE_PAYMENT_QUEUE_NAME            |      | Name of the queue for close payment events                                                                                                                                      | string  |         |
| TRANSACTION_NOTIFICATIONS_QUEUE_NAME            |      | Name of the queue for notification requested events                                                                                                                             | string  |         |
| TRANSACTION_REFUND_QUEUE_NAME                   |      | Name of the refund queue for transactions that receive a closePayment with OK authorization and KO outcome                                                                      | string  |         |
| TRANSIENT_QUEUES_TTL_SECONDS                    |      | TTL to be used when sending events on transient queues                                                                                                                          | number  | 7 days  |
| TRANSACTIONS_RETRY_OFFSET                       |      | Seconds to offset validity end to account for more retries                                                                                                                      | number  |         |
| CLOSURE_RETRY_INTERVAL                          |      | Seconds to wait at closing the transaction before making a retry                                                                                                                | number  |         |
| PERSONAL_DATA_VAULT_API_KEY                     |      | API Key for Personal Data Vault (PDV is used to safely encrypt PIIs, e.g. the user's email address)                                                                             | string  |         |
| PERSONAL_DATA_VAULT_API_BASE_PATH               |      | API base path for Personal Data Vault                                                                                                                                           | string  |         |
| NPG_API_KEY                                     |      | API Key for Nuovo Payment Gateway (NPG, used for authorizing payments).                                                                                                         | string  |         |
| NPG_URI                                         |      | NPG connection uri                                                                                                                                                              | string  |         |
| NPG_READ_TIMEOUT                                |      | Timeout for requests towards NPG                                                                                                                                                | string  |         |
| NPG_CONNECTION_TIMEOUT                          |      | Timeout for establishing connections towards NPG                                                                                                                                | string  |         |
| NPG_CARDS_PSP_KEYS                              |      | Secret structure that holds psp - api keys association for authorization request                                                                                                | string  |         |
| NPG_CARDS_PSP_LIST                              |      | List of all psp ids that are expected to be found into the NPG_CARDS_PSP_KEYS configuration (used for configuration cross validation)                                           | string  |         |
| CHECKOUT_BASE_PATH                              |      | Checkout basepath where the user will be brought to after the authorization process is completed                                                                                | string  |         |
| ECOMMERCE_EVENT_VERSION                         |      | Ecommerce event version used during transaction activated value accepted V1 and v2                                                                                              | string  |         |
| SESSION_URL_BASEPATH                            |      | Url used into npg order build request to enhance the merchantUrl field                                                                                                          | string  |         |
| SESSION_URL_OUTCOME_SUFFIX                      |      | Suffix concatenated to the merchant url to enhance the resultUrl field in the order build to NPG                                                                                | string  |         |
| SESSION_URL_NOTIFICATION_URL                    |      | Url used into npg order build request to enhance the notificationUrl field                                                                                                      | string  |         |
| NODE_FORWARDER_API_KEY                          |      | Node forwarder api key                                                                                                                                                          | string  |         |
| REDIRECT_PAYMENT_TYPE_CODE_LIST                 |      | List of all redirect payment type codes that are expected to be present in other redirect configurations such as REDIRECT_URL_MAPPING (used for configuration cross validation) | string  |         |
| REDIRECT_PAYMENT_TYPE_CODE_DESCRIPTION_MAPPING  |      | Redirect Payment type code to description mapping                                                                                                                               | string  |         |
| NODE_FORWARDER_URL                              |      | Node forwarder backend URL                                                                                                                                                      | string  |         |
| REDIRECT_URL_MAPPING                            |      | Key-value string map PSP to backend URI mapping that will be used for Redirect payments                                                                                         | string  |         |
| NODE_FORWARDER_READ_TIMEOUT                     |      | Node forwarder request read timeout                                                                                                                                             | number  |         |
| NODE_FORWARDER_CONNECTION_TIMEOUT               |      | Node forwarder request connection timeout                                                                                                                                       | number  |         |
| TRANSACTIONS_AUTHORIZATION_REQUESTED_QUEUE_NAME |      | Name of the queue for transaction payment gateway polling for authorization requested transactions                                                                              | string  |         |
| AUTH_REQUESTED_EVENT_VISIBILITY_TIMEOUT_SECONDS |      | Authorization requested event visibility timeout in seconds                                                                                                                     | number  |         |
| NPG_PAYPAL_PSP_KEYS                             |      | Secret structure that holds psp - api keys association for authorization request used for APM PAYPAL payment method                                                             | string  |         |
| NPG_PAYPAL_PSP_LIST                             |      | List of all psp ids that are expected to be found into the NPG_PAYPAL_PSP_KEYS configuration (used for configuration cross validation)                                          | string  |         |
| NPG_BANCOMATPAY_PSP_KEYS                        |      | Secret structure that holds psp - api keys association for authorization request used for APM Bancomat pay payment method                                                       | string  |         |
| NPG_BANCOMATPAY_PSP_LIST                        |      | List of all psp ids that are expected to be found into the NPG_BANCOMATPAY_PSP_KEYS configuration (used for configuration cross validation)                                     | string  |         |
| NPG_MYBANK_PSP_KEYS                             |      | Secret structure that holds psp - api keys association for authorization request used for APM My bank payment method                                                            | string  |         |
| NPG_MYBANK_PSP_LIST                             |      | List of all psp ids that are expected to be found into the NPG_MYBANK_PSP_LIST configuration (used for configuration cross validation)                                          | string  |         |
| NPG_SATISPAY_PSP_KEYS                           |      | Secret structure that holds psp - api keys association for authorization request used for APM Satispay payment method                                                           | string  |         |
| NPG_SATISPAY_PSP_LIST                           |      | List of all psp ids that are expected to be found into the NPG_SATISPAY_PSP_KEYS configuration (used for configuration cross validation)                                        | string  |         |
| NPG_APPLEPAY_PSP_KEYS                           |      | Secret structure that holds psp - api keys association for authorization request used for APM Apple pay payment method                                                          | string  |         |
| NPG_APPLEPAY_PSP_LIST                           |      | List of all psp ids that are expected to be found into the NPG_APPLEPAY_PSP_KEYS configuration (used for configuration cross validation)                                        | string  |         |
| NPG_AUTHORIZATION_EXCLUDED_ERROR_CODES          |      | NPG error codes for which eCommerce will not perform retry during authorization request                                                                                         | string  |         |
| EXCLUSIVE_LOCK_DOCUMENT_TTL_SECONDS             |      | Exclusive lock Redis document TTL for authorization status update (in seconds)                                                                                                  | number  | 2       |
| NPG_GOOGLE_PAY_PSP_KEYS                         |      | Secret structure that holds psp - api keys association for authorization request used for APM Google pay payment method                                                         | string  |         |
| NPG_GOOGLE_PAY_PSP_LIST                         |      | List of all psp ids that are expected to be found into the NPG_GOOGLE_PAY_PSP_KEYS configuration (used for configuration cross validation)                                      | string  |         |
| NPG_AUTHORIZATION_ERROR_CODE_MAPPING            |      | Map to bind authorization error code received from NPG to the right outcome code. This info is used when NPG authorization status is DECLINED                                   | string  |         |
| ECOMMERCE_FINAL_STATES                          |      | List of all ecommerce transactions final states                                                                                                                                 | string  |         |
| ECOMMERCE_POSSIBLE_FINAL_STATES                 |      | List of all ecommerce transactions possible final states that requires more condition check to be sure to declare it as final to the touchpoints                                | string  |         |
| JWT_ISSUER_URI                                  |      | JWT Issuer URI                                                                                                                                                                  | string  |         |
| JWT_ISSUER_READ_TIMEOUT                         |      | Timeout for requests towards JWT Issuer                                                                                                                                         | string  |         |
| JWT_ISSUER_CONNECTION_TIMEOUT                   |      | Timeout for establishing connections towards JWT Issuer                                                                                                                         | string  |         |
| JWT_ISSUER_API_KEY                              |      | Jwt issuer service API key                                                                                                                                                      | string  |         |
| NODO_NODEFORPSP_API_KEY                         |      | API Key for NODE FOR PSP WS                                                                                                                                                     | string  |         |
| NODO_NODEFORECOMMERCE_API_KEY                   |      | API Key for Nodo closePayment API                                                                                                                                               | string  |         |
| SECURITY_API_KEYS_SECURED_PATHS                 |      | Comma-separated list of secured API paths                                                                                                                                       | string  |         |
| SECURITY_API_KEYS_PRIMARY                       |      | Secured api primary key                                                                                                                                                         | string  |         |
| SECURITY_API_KEYS_SECONDARY                     |      | Secured api secondary key                                                                                                                                                       | string  |         |
| TRANSACTIONSVIEW_UPDATE_ENABLED                 |      | Feature flag to enable/disable view writing                                                                                                                                     | boolean | true    |

An example configuration of these environment variables is in the `.env.example` file.

(*): for Mongo connection string options
see [docs](https://www.mongodb.com/docs/drivers/java/sync/v4.3/fundamentals/connection/connection-options/#connection-options)

## Run the application with `Docker`

### Prerequisites
Set up GitHub authentication for packages (required for pagopa-ecommerce-commons dependency):

1. Configure Maven settings file:
- **If you don't have ~/.m2/settings.xml:**
	```sh
	cp settings.xml.template ~/.m2/settings.xml
	```
- **If you already have ~/.m2/settings.xml:** Edit the file to add the GitHub server configuration from `settings.xml.template`, or replace the `${GITHUB_TOKEN}` placeholder with your actual token.


2. Set your GitHub token:
```sh
export GITHUB_TOKEN=your_github_token_with_packages_read_permission
```

**Note:** The settings.xml file is required for Maven to authenticate with GitHub Packages. Without proper configuration, builds will fail with 401 Unauthorized errors.

### Build Docker Image
```sh
docker build --secret id=GITHUB_TOKEN,env=GITHUB_TOKEN -t pagopa-ecommerce-transactions-service .
```

### Run with Docker Compose
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
interfaces (Mongo express/Redis Insight). To do so go to:

* http://localhost:8001 for Redis Insight
* http://localhost:8081 for Mongo Express

## Run the application with `springboot-plugin`

### Prerequisites
Set up GitHub authentication for packages (required for pagopa-ecommerce-commons dependency):

1. Configure Maven settings file:
- **If you don't have ~/.m2/settings.xml:**
	```sh
	cp settings.xml.template ~/.m2/settings.xml
	```
- **If you already have ~/.m2/settings.xml:** Edit the file to add the GitHub server configuration from `settings.xml.template`, or replace the `${GITHUB_TOKEN}` placeholder with your actual token.


2. Create your environment:
```sh
export $(grep -v '^#' .env.local | xargs)
```

3. Set your GitHub token:
```sh
export GITHUB_TOKEN=your_github_token_with_packages_read_permission
```

Then from current project directory run :

```sh
mvn spring-boot:run
```

**Note:** The application now uses pagopa-ecommerce-commons library directly from GitHub Packages. Make sure your GitHub token has `packages:read` permission for the `pagopa/pagopa-ecommerce-commons` repository.

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

## Dependency Verification

This project uses the [pagopa/depcheck](https://github.com/pagopa/depcheck) Maven plugin to verify SHA-256 hashes of all dependencies, ensuring supply chain integrity and preventing dependency tampering attacks.

### How It Works
The plugin maintains a JSON file containing SHA-256 hashes of all project dependencies. During verification, it compares the hashes of resolved artifacts against the stored values, failing the build if any mismatches are detected.

### Configuration

```xml
<plugin>
<groupId>it.pagopa.maven</groupId>
<artifactId>depcheck</artifactId>
<version>1.3.0</version>
<configuration>
	<fileName>dep-sha256.json</fileName>
	<includePlugins>false</includePlugins>
	<includeParent>false</includeParent>
	<excludes>
	<!-- Optional: Exclude specific dependencies -->
	</excludes>
</configuration>
<executions>
	<execution>
	<phase>validate</phase>
	<goals>
		<goal>verify</goal>
	</goals>
	</execution>
</executions>
</plugin>
```

### Usage
First of all, ensure your GitHub token and `settings.xml` are properly configured.

1. **Generate hashes**: When adding new dependencies or updating existing ones:
```sh
mvn depcheck:generate
```
**NOTE**: Always commit the updated hash file to version control after adding or updating dependencies

2. **Verify hashes**: This happens automatically during the `validate` phase, and so, automatically, in CI/CD pipelines.
You can also explicitly run:
```sh
mvn depcheck:verify
```

### Important Notes

- **Maven plugins** have empty SHA-256 values by default as they're not resolved as JAR files during the regular build. Right now `includePlugins=false` avoid empty hashes and plugin check.
