# FRED Integration

This service integrates with the [Federal Reserve Economic Data (FRED)](https://fred.stlouisfed.org/) API, maintained by the Federal Reserve Bank of St. Louis. FRED is a comprehensive database of 800,000+ economic time series from dozens of national, international, public, and private sources.

## Data Source

Exchange rate data is imported from the [Daily Rates category](https://fred.stlouisfed.org/categories/94):

- **Category path**: Money, Banking, & Finance > Exchange Rates > Daily Rates
- **Available series**: USD/EUR, USD/GBP, USD/JPY, USD/CNY, and 20+ other currency pairs
- **Dollar indices**: Nominal Broad, Advanced Foreign Economies, Emerging Markets
- **Historical depth**: Data back to 1971 for major currencies

## API Key

FRED requires a free API key. Register at https://fred.stlouisfed.org/docs/api/api_key.html

Set the key as an environment variable:

```bash
export FRED_API_KEY=your_api_key_here
```

## Provider Architecture

The service layer depends on the `ExchangeRateProvider` interface, never on the concrete FRED implementation. This decouples the service from the external data source and allows switching providers without service layer changes.

```
CurrencyService
    | (depends on interface)
ExchangeRateProvider (interface)
    ^ (implements)
FredExchangeRateProvider
    | (uses)
FredClient (HTTP communication)
```

See [advanced-patterns-usage.md](advanced-patterns-usage.md#provider-abstraction-pattern) for implementation details and examples of adding new providers.

## Expandability

The FRED API provides access to economic data far beyond exchange rates. With the existing client infrastructure and API key, future Budget Analyzer services could import:

- **Interest rates** - Treasury yields, Fed funds rate, LIBOR
- **Inflation** - CPI, PCE, producer prices
- **Employment** - Unemployment rate, payrolls, labor force
- **GDP & output** - Real GDP, industrial production
- **Regional data** - State and metro area statistics

See the full [FRED categories](https://fred.stlouisfed.org/categories) for available data.

## Import Schedule

- **Frequency**: Daily at 11 PM UTC
- **Coordination**: ShedLock distributed lock prevents duplicate imports in multi-pod deployments
- **Behavior**: Imports last 30 days for all active series, skips existing dates (idempotent)
- **Lock parameters**: `lockAtMostFor: 15m`, `lockAtLeastFor: 1m`

See [advanced-patterns-usage.md](advanced-patterns-usage.md#shedlock-distributed-locking) for distributed scheduling details.

## FRED API Rate Limits

- 120 requests per minute
- Handled internally with retry and backoff
