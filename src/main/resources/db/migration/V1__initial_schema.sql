-- Initial schema for Currency Service
-- This migration represents the baseline schema from the ExchangeRate entity

-- Create exchange_rate table
CREATE TABLE exchange_rate (
    id BIGSERIAL PRIMARY KEY,
    base_currency VARCHAR(3) NOT NULL,
    target_currency VARCHAR(3) NOT NULL,
    date DATE NOT NULL,
    rate NUMERIC(38, 4),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT uk_exchange_rate_currency_date UNIQUE (base_currency, target_currency, date)
);

-- Create indexes for common query patterns
CREATE INDEX idx_exchange_rate_target_currency ON exchange_rate(target_currency);
CREATE INDEX idx_exchange_rate_date ON exchange_rate(date);
CREATE INDEX idx_exchange_rate_target_date ON exchange_rate(target_currency, date);

-- Add comments for documentation
COMMENT ON TABLE exchange_rate IS 'Stores historical exchange rates between currency pairs';
COMMENT ON COLUMN exchange_rate.base_currency IS 'ISO 4217 base currency code (e.g., USD)';
COMMENT ON COLUMN exchange_rate.target_currency IS 'ISO 4217 target currency code (e.g., EUR)';
COMMENT ON COLUMN exchange_rate.date IS 'Date for which the exchange rate is valid';
COMMENT ON COLUMN exchange_rate.rate IS 'Exchange rate value with 4 decimal precision';
COMMENT ON COLUMN exchange_rate.created_at IS 'Timestamp with timezone when the record was created (UTC)';
COMMENT ON COLUMN exchange_rate.updated_at IS 'Timestamp with timezone when the record was last updated (UTC)';
