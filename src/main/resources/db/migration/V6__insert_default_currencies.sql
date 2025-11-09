-- Add unique constraint to provider_series_id and insert all supported currencies
--
-- Part 1: Add unique constraint to provider_series_id
-- This ensures one-to-one mapping between provider series IDs and currency codes.
-- Each FRED series ID (e.g., DEXUSEU) can only be associated with one currency.
--
-- Part 2: Insert default currencies
-- This migration populates the currency_series table with all pre-configured
-- currency-to-provider-series mappings. All currencies are initially disabled,
-- allowing users to enable only the currencies they need.
--
-- Data source: fred-currency-mappings.csv
-- Provider: FRED (Federal Reserve Economic Data)
-- Exchange rate basis: All rates are USD-based (e.g., EUR/USD, JPY/USD)
--
-- Idempotency: Provided by Flyway - this migration runs exactly once per database
-- Each test run gets a fresh H2 database, so no conflicts

-- Add unique constraint to provider_series_id
ALTER TABLE currency_series
ADD CONSTRAINT uk_currency_series_provider_series_id UNIQUE (provider_series_id);

-- Insert all supported currencies as disabled by default
INSERT INTO currency_series (currency_code, provider_series_id, enabled)
VALUES
  ('AUD', 'DEXUSAL', false),  -- Australian Dollar
  ('BRL', 'DEXBZUS', false),  -- Brazilian Real
  ('CAD', 'DEXCAUS', false),  -- Canadian Dollar
  ('CHF', 'DEXSZUS', false),  -- Swiss Franc
  ('CNY', 'DEXCHUS', false),  -- Chinese Yuan
  ('DKK', 'DEXDNUS', false),  -- Danish Krone
  ('EUR', 'DEXUSEU', false),  -- Euro
  ('GBP', 'DEXUSUK', false),  -- British Pound
  ('HKD', 'DEXHKUS', false),  -- Hong Kong Dollar
  ('INR', 'DEXINUS', false),  -- Indian Rupee
  ('JPY', 'DEXJPUS', false),  -- Japanese Yen
  ('KRW', 'DEXKOUS', false),  -- South Korean Won
  ('LKR', 'DEXSLUS', false),  -- Sri Lankan Rupee
  ('MXN', 'DEXMXUS', false),  -- Mexican Peso
  ('MYR', 'DEXMAUS', false),  -- Malaysian Ringgit
  ('NOK', 'DEXNOUS', false),  -- Norwegian Krone
  ('NZD', 'DEXUSNZ', false),  -- New Zealand Dollar
  ('SEK', 'DEXSDUS', false),  -- Swedish Krona
  ('SGD', 'DEXSIUS', false),  -- Singapore Dollar
  ('THB', 'DEXTHUS', false),  -- Thai Baht
  ('TWD', 'DEXTAUS', false),  -- Taiwan Dollar
  ('VEF', 'DEXVZUS', false),  -- Venezuelan Bolivar
  ('ZAR', 'DEXSFUS', false);  -- South African Rand

-- Note: created_at and updated_at will be automatically set to CURRENT_TIMESTAMP
-- by the table's DEFAULT constraints
