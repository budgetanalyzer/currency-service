# Configuration

## Infrastructure Dependencies

| Component | Purpose | Local Default |
|---|---|---|
| PostgreSQL | Data persistence + ShedLock storage | `localhost:5432`, database `currency` |
| Redis | Distributed caching | `localhost:6379`, TLS enabled |
| RabbitMQ | Event-driven messaging | `localhost:5671`, AMQPS with TLS |

## Environment Variables

### Required

| Variable | Description |
|---|---|
| `FRED_API_KEY` | FRED API key ([register here](https://fred.stlouisfed.org/docs/api/api_key.html)) |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password for `currency_service` user |
| `SPRING_RABBITMQ_PASSWORD` | RabbitMQ password for `currency-service` identity |
| `SPRING_DATA_REDIS_PASSWORD` | Redis password for `currency-service` identity |

### Optional (with defaults)

| Variable | Default | Description |
|---|---|---|
| `SPRING_RABBITMQ_HOST` | `localhost` | RabbitMQ hostname |
| `SPRING_RABBITMQ_PORT` | `5671` | RabbitMQ port (AMQPS) |
| `SPRING_RABBITMQ_SSL_ENABLED` | `true` | Enable RabbitMQ TLS |
| `SPRING_RABBITMQ_SSL_BUNDLE` | `infra-ca` | TLS bundle name |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis hostname |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_DATA_REDIS_SSL_ENABLED` | `true` | Enable Redis TLS |
| `SPRING_DATA_REDIS_SSL_BUNDLE` | `infra-ca` | TLS bundle name |
| `INFRA_CA_CERT_PATH` | — | Path to infrastructure CA certificate PEM |

## Caching

- **Type**: Redis (cache-aside pattern)
- **Exchange rates TTL**: 1 hour
- **Latest rate TTL**: 30 minutes
- **Currency series TTL**: 6 hours
- **Expected hit rate**: 80-95%
- **Cache eviction**: Automatic on import, manual via admin endpoint, TTL expiration

See [advanced-patterns-usage.md](advanced-patterns-usage.md#redis-distributed-caching) for implementation details.

## Scheduled Tasks

| Task | Schedule | Lock Duration | Description |
|---|---|---|---|
| Daily import | `0 0 23 * * *` (11 PM UTC) | 15m max, 1m min | Import rates for all active series |

See [advanced-patterns-usage.md](advanced-patterns-usage.md#shedlock-distributed-locking) for distributed locking details.

## Event-Driven Messaging

- **Exchange**: `currency.exchange` (topic)
- **Routing key prefix**: `currency`
- **Delivery guarantee**: Transactional outbox via Spring Modulith
- **Events**: `CurrencySeriesCreated`, `ExchangeRatesImported`, `ImportFailed`

See [advanced-patterns-usage.md](advanced-patterns-usage.md#event-driven-messaging) for the outbox pattern and examples.

## Security

The service integrates with the platform security model:
- **API Gateway** (Envoy) handles routing with ext_authz session validation
- **Session Gateway** manages OAuth2 login and Redis-backed sessions
- **Service Common** provides claims-header security reading pre-validated `X-User-Id`, `X-Permissions`, `X-Roles` headers
- **Permission Service** provides fine-grained authorization (e.g., `currencies:read`, `currencies:write`)
