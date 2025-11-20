-- Add user tracking columns for audit trail
-- These columns track who created and last modified each record

-- Add columns to exchange_rate table
ALTER TABLE exchange_rate ADD COLUMN created_by VARCHAR(50);
ALTER TABLE exchange_rate ADD COLUMN updated_by VARCHAR(50);

-- Add columns to currency_series table
ALTER TABLE currency_series ADD COLUMN created_by VARCHAR(50);
ALTER TABLE currency_series ADD COLUMN updated_by VARCHAR(50);

-- Add indexes for common query patterns
CREATE INDEX idx_exchange_rate_created_by ON exchange_rate(created_by);
CREATE INDEX idx_exchange_rate_updated_by ON exchange_rate(updated_by);
CREATE INDEX idx_currency_series_created_by ON currency_series(created_by);
CREATE INDEX idx_currency_series_updated_by ON currency_series(updated_by);

-- Add comments for documentation
COMMENT ON COLUMN exchange_rate.created_by IS 'User ID who created this exchange rate (Auth0 sub claim or system)';
COMMENT ON COLUMN exchange_rate.updated_by IS 'User ID who last modified this exchange rate (Auth0 sub claim or system)';
COMMENT ON COLUMN currency_series.created_by IS 'User ID who created this currency series (Auth0 sub claim or system)';
COMMENT ON COLUMN currency_series.updated_by IS 'User ID who last modified this currency series (Auth0 sub claim or system)';
