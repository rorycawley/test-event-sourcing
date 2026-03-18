# Testcontainers -- Disposable Test Infrastructure

[Testcontainers](https://www.testcontainers.org/) runs Docker containers for integration tests. Each test gets a fresh, disposable database (or message broker) that is destroyed when the test ends. No shared state between test runs.

**Dependency:** `org.testcontainers/postgresql {:mvn/version "1.21.4"}`
**Dependency:** `org.testcontainers/rabbitmq {:mvn/version "1.21.4"}`

## Java Classes Used

```clojure
(:import [org.testcontainers.containers PostgreSQLContainer RabbitMQContainer]
         [org.testcontainers.utility DockerImageName])
```

---

## How It Works

1. Testcontainers pulls a Docker image (if not cached)
2. Starts a container with a random available port
3. Returns connection details (host, port, credentials)
4. Your code connects using those details
5. When the test ends, the container is stopped and removed

**Prerequisites:** Docker must be running.

---

## PostgreSQL Container

### Starting

```clojure
(defn start-postgres!
  [& {:keys [image] :or {image "paradedb/paradedb:latest"}}]
  (let [docker-image (-> (DockerImageName/parse image)
                         (.asCompatibleSubstituteFor "postgres"))
        container    (doto (PostgreSQLContainer. docker-image)
                       (.start))]
    {:container container
     :jdbc-url  (.getJdbcUrl container)
     :username  (.getUsername container)
     :password  (.getPassword container)}))
```

Step by step:

1. **`DockerImageName/parse`** -- parses the image name string
2. **`.asCompatibleSubstituteFor "postgres"`** -- tells Testcontainers this image is Postgres-compatible (needed for ParadeDB since it's not the official `postgres` image)
3. **`PostgreSQLContainer.`** -- creates the container object
4. **`doto ... .start`** -- starts the container (blocks until ready)
5. **`.getJdbcUrl`** etc. -- extract connection details

### Stopping

```clojure
(defn stop-postgres! [{:keys [container]}]
  (when container
    (.stop container)))
```

### Creating a Datasource

```clojure
(defn ->datasource [pg]
  (jdbc/get-datasource {:jdbcUrl  (:jdbc-url pg)
                        :user     (:username pg)
                        :password (:password pg)}))
```

### Complete Usage Pattern

```clojure
(let [pg (infra/start-postgres!)
      ds (infra/->datasource pg)]
  (try
    (migrations/migrate! ds)
    ;; ... run tests or application code ...
    (finally
      (infra/stop-postgres! pg))))
```

---

## RabbitMQ Container

### Starting

```clojure
(defn start-rabbitmq! []
  (let [container (doto (RabbitMQContainer.
                          (DockerImageName/parse "rabbitmq:3-management-alpine"))
                    (.start))]
    {:container container
     :host      (.getHost container)
     :port      (.getAmqpPort container)
     :username  "guest"
     :password  "guest"}))
```

### Stopping

```clojure
(defn stop-rabbitmq! [{:keys [container]}]
  (when container
    (.stop container)))
```

---

## Test Fixture Pattern

Tests use Clojure's `use-fixtures` to manage container lifecycle:

### `:once` Fixture -- One Container Per Test Namespace

```clojure
(def ^:dynamic *ds* nil)

(defn- with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))                            ;; run all tests in this namespace
      (finally
        (infra/stop-postgres! pg)))))

(use-fixtures :once with-system)
```

### `:each` Fixture -- Clean State Per Test

```clojure
(defn- with-clean-db [f]
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx ["TRUNCATE TABLE events, idempotency_keys
                            RESTART IDENTITY"]))
  (f))                                  ;; run one test

(use-fixtures :each with-clean-db)
```

### Combined

```clojure
(use-fixtures :once with-system)    ;; start container once
(use-fixtures :each with-clean-db)  ;; clean data before each test
```

This means: start one Postgres container for the entire test file, but truncate tables before each individual test.

---

## Two-Container Pattern (Async Tests)

For testing the async pipeline, the project starts multiple containers:

```clojure
(defn- with-system [f]
  (let [event-pg (infra/start-postgres!)
        event-ds (infra/->datasource event-pg)
        read-pg  (infra/start-postgres!)
        read-ds  (infra/->datasource read-pg)]
    (try
      (migrations/migrate! event-ds)
      (migrations/migrate! read-ds :migration-dir "read-migrations")
      (binding [*event-store-ds* event-ds
                *read-db-ds*     read-ds]
        (f))
      (finally
        (infra/stop-postgres! read-pg)
        (infra/stop-postgres! event-pg)))))
```

---

## ParadeDB Image

This project uses [ParadeDB](https://www.paradedb.com/) instead of vanilla PostgreSQL. ParadeDB is PostgreSQL with the `pg_search` extension for BM25 full-text search:

```clojure
(def ^:const default-postgres-image "paradedb/paradedb:latest")
```

The `.asCompatibleSubstituteFor "postgres"` call tells Testcontainers to treat it like a standard PostgreSQL container (same port, same protocol).

---

## Performance Tips

- **Container startup takes 2-5 seconds.** Use `:once` fixtures to share one container across all tests in a namespace.
- **TRUNCATE is fast.** Use `:each` fixtures for data cleanup, not container restart.
- **Docker layer caching** means the image is only pulled once. Subsequent starts use the cached image.
- **Parallel test namespaces** each get their own containers (separate ports). No conflicts.
