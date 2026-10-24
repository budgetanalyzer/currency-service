# FRED Integration

This service integrates with the [Federal Reserve Economic Data (FRED)](https://fred.stlouisfed.org/) API, maintained by the Federal Reserve Bank of St. Louis. FRED is a comprehensive database of 800,000+ economic time series from dozens of national, international, public, and private sources.

## Data Source

Exchange rate data is imported from the [Daily Rates category](https://fred.stlouisfed.org/categories/94):

- **Category path**: Money, Banking, & Finance > Exchange Rates > Daily Rates
- **Available series**: USD/EUR, USD/GBP, USD/JPY, USD/CNY, and 20+ other currency pairs
- **Dollar indices**: Nominal Broad, Advanced Foreign Economies, Emerging Markets
- **Historical depth**: Data back to 1971 for major currencies

Series IDs follow two naming conventions, and the import stores each in its natural direction:

- `DEXUS*` (for example `DEXUSEU`) is USD per unit of foreign currency, stored with the foreign
  currency as base and USD as target.
- `DEX*US` (for example `DEXJPUS`) is foreign currency per USD, stored with USD as base and the
  foreign currency as target.

## API Key

FRED requires a free API key. Register at https://fred.stlouisfed.org/docs/api/api_key.html

Set the key as an environment variable:

```bash
export FRED_API_KEY=your_api_key_here
```

`FRED_API_KEY` has no default, so the service does not start without it. The FRED base URL and
request timeout are listed in [configuration.md](configuration.md#application-properties).

## Provider Architecture

The service layer depends on the `ExchangeRateProvider` interface, never on the concrete FRED
implementation. Two services use it:

```
CurrencyService (validates provider series IDs)     ExchangeRateImportService (imports rates)
            \                                                    /
             '------------> ExchangeRateProvider (interface) <--'
                                      ^ implements
                            FredExchangeRateProvider
                                      | uses
                            FredClient (HTTP to the FRED API)
```

- `CurrencyService.create` calls `validateSeriesExists` with one request to FRED's `/series`
  endpoint. HTTP 200 means the series exists; 400 or 404 means it does not.
- Imports call `getExchangeRates` with one request to `/series/observations` per currency series.
  FRED marks a missing observation, such as a weekend or holiday, with the value `.`. The provider
  drops those observations.

See [advanced-patterns-usage.md](advanced-patterns-usage.md#provider-abstraction-pattern) for the
code and how to add a provider.

## Expandability

The FRED API provides access to economic data far beyond exchange rates. With the existing client infrastructure and API key, future Budget Analyzer services could import:

- **Interest rates** - Treasury yields, federal funds rate
- **Inflation** - CPI, PCE, producer prices
- **Employment** - Unemployment rate, payrolls, labor force
- **GDP & output** - Real GDP, industrial production
- **Regional data** - State and metro area statistics

See the full [FRED categories](https://fred.stlouisfed.org/categories) for available data.

## Import Behavior

### When Imports Run

| Trigger | Scope |
|---|---|
| Daily schedule, 23:00 UTC | Every enabled series |
| Application startup, when `import-on-startup` is `true` | Every enabled series |
| `POST /v1/exchange-rates/import` | Every enabled series |
| An `exchange-rate.import.requested` message, sent after an enabled currency is created or a disabled one is enabled | One series |

The schedule, lock, and retry settings are in
[configuration.md](configuration.md#scheduled-tasks). See
[advanced-patterns-usage.md](advanced-patterns-usage.md#shedlock-distributed-locking) for
distributed scheduling details.

### Start Date

For each series, the import finds the latest stored rate for its base and target currency pair:

- If a rate is stored, the import requests observations starting the day after it
  (`observation_start`).
- If no rate is stored, the import requests the full history (no `observation_start`).

### Saving Rates

- **Initial import** (no stored rate for the pair): every fetched rate is inserted.
- **Later imports:** each fetched date is compared with the stored rate for that date:
  - no stored rate: inserted and counted as `newRecords`;
  - a different stored rate: overwritten, logged as a warning, and counted as `updatedRecords`;
  - the same stored rate: left unchanged and counted as `skippedRecords`.

Because later imports start after the latest stored date, FRED normally returns only new dates.
The comparison handles any already-stored date that does come back. An import that fetches no
observations reports zero counts.

An all-series import runs in one transaction. If any series fails, the whole import rolls back and
the cache is not evicted. A successful import evicts all cached exchange-rate queries after it
commits.

## FRED API Rate Limits

FRED allows up to 120 requests per minute and answers further requests with HTTP 429 (see the
[FRED API errors page](https://fred.stlouisfed.org/docs/api/fred/errors.html)). An all-series
import makes one request per enabled series.

The service does not throttle its requests and does not retry FRED calls inside the client.
`FredClient` turns every FRED error response, including 429, into a `ClientException` that carries
FRED's error message and code. It does the same for timeouts and connection failures. What happens
next depends on the caller:

| Caller | Result of a `ClientException` |
|---|---|
| Daily schedule | The import rolls back. The scheduler retries it as configured by `currency-service.exchange-rate-import.retry.*`. |
| Startup import | Startup fails. |
| `POST /v1/exchange-rates/import` | The import rolls back and the request returns `503`. |
| Import request message | Spring Cloud Stream retries the message, then moves it to the dead-letter queue. See [configuration.md](configuration.md#consumer-retry-and-dead-letters). |
| `POST /v1/currencies` series validation | The request returns `503` and the currency is not created. |

Besides `fred.timeout-seconds`, the HTTP client in `WebClientConfig` fails a request after a 5 s
connect timeout or 10 s without receiving data.
