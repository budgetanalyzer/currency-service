package com.bleurubin.budgetanalyzer.currency.service;

import java.util.Currency;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;
import com.bleurubin.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;
import com.bleurubin.budgetanalyzer.currency.messaging.publisher.CurrencyMessagePublisher;
import com.bleurubin.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import com.bleurubin.budgetanalyzer.currency.service.provider.ExchangeRateProvider;
import com.bleurubin.service.exception.BusinessException;
import com.bleurubin.service.exception.ClientException;
import com.bleurubin.service.exception.ResourceNotFoundException;
import com.bleurubin.service.exception.ServiceUnavailableException;

/** Service for managing currency operations. */
@Service
public class CurrencyService {

  private final CurrencySeriesRepository currencySeriesRepository;
  private final ExchangeRateProvider exchangeRateProvider;
  private final CurrencyMessagePublisher messagePublisher;

  /**
   * Constructor for CurrencyService.
   *
   * @param currencySeriesRepository The currency series repository
   * @param exchangeRateProvider The exchange rate provider
   * @param messagePublisher The message publisher for currency events
   */
  public CurrencyService(
      CurrencySeriesRepository currencySeriesRepository,
      ExchangeRateProvider exchangeRateProvider,
      CurrencyMessagePublisher messagePublisher) {
    this.currencySeriesRepository = currencySeriesRepository;
    this.exchangeRateProvider = exchangeRateProvider;
    this.messagePublisher = messagePublisher;
  }

  /**
   * Create a new currency series.
   *
   * @param currencySeries The currency series to create
   * @return The created currency series
   * @throws BusinessException if currency code is invalid or already exists, or if provider series
   *     ID is invalid
   */
  @Transactional
  public CurrencySeries create(CurrencySeries currencySeries) {
    validateCurrencyCode(currencySeries.getCurrencyCode());
    validateProviderSeriesId(currencySeries.getProviderSeriesId());

    try {
      var saved = currencySeriesRepository.save(currencySeries);

      // Publish message after successful save to trigger async exchange rate import
      messagePublisher.publishCurrencyCreated(
          new CurrencyCreatedMessage(saved.getId(), saved.getCurrencyCode()));

      return saved;
    } catch (DataIntegrityViolationException e) {
      throw new BusinessException(
          "Currency code '" + currencySeries.getCurrencyCode() + "' already exists",
          CurrencyServiceError.DUPLICATE_CURRENCY_CODE.name());
    }
  }

  /**
   * Get a currency series by ID.
   *
   * @param id The currency series ID
   * @return The currency series
   * @throws ResourceNotFoundException if currency series not found
   */
  @Transactional(readOnly = true)
  public CurrencySeries getById(Long id) {
    return currencySeriesRepository
        .findById(id)
        .orElseThrow(
            () -> new ResourceNotFoundException("Currency series not found with id: " + id));
  }

  /**
   * Get all currency series.
   *
   * @param enabledOnly If true, return only enabled currency series; if false, return all
   * @return List of currency series
   */
  @Transactional(readOnly = true)
  public List<CurrencySeries> getAll(boolean enabledOnly) {
    return enabledOnly
        ? currencySeriesRepository.findByEnabledTrue()
        : currencySeriesRepository.findAll();
  }

  /**
   * Update an existing currency series.
   *
   * <p>Note: Currency code is immutable and cannot be changed. Only providerSeriesId and enabled
   * can be updated.
   *
   * @param id The currency series ID
   * @param providerSeriesId The new provider series ID
   * @param enabled The new enabled status
   * @return The updated currency series
   * @throws ResourceNotFoundException if currency series not found
   * @throws BusinessException if provider series ID is invalid
   */
  @Transactional
  public CurrencySeries update(Long id, String providerSeriesId, boolean enabled) {
    var currencySeries = getById(id);
    validateProviderSeriesId(providerSeriesId);
    currencySeries.setProviderSeriesId(providerSeriesId);
    currencySeries.setEnabled(enabled);

    return currencySeriesRepository.save(currencySeries);
  }

  /**
   * Validate that the currency code is a valid ISO 4217 code.
   *
   * <p>Note: Format validation (3 uppercase letters) is already handled by Bean Validation in the
   * request DTO. This method only checks ISO 4217 validity.
   *
   * @param currencyCode The currency code to validate
   * @throws BusinessException if currency code is not a valid ISO 4217 code
   */
  private void validateCurrencyCode(String currencyCode) {
    try {
      Currency.getInstance(currencyCode);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(
          "Invalid ISO 4217 currency code: " + currencyCode,
          CurrencyServiceError.INVALID_ISO_4217_CODE.name());
    }
  }

  /**
   * Validate that the provider series ID exists in the external provider.
   *
   * <p>Note: Format validation is already handled by Bean Validation in the request DTO. This
   * method validates that the series ID exists in the external provider.
   *
   * @param providerSeriesId The provider series ID to validate
   * @throws BusinessException if provider series ID does not exist or validation fails
   */
  private void validateProviderSeriesId(String providerSeriesId) {
    boolean exists;
    try {
      exists = exchangeRateProvider.validateSeriesExists(providerSeriesId);
    } catch (ClientException e) {
      throw new ServiceUnavailableException(
          "Unable to validate provider series ID due to external provider API error: "
              + e.getMessage(),
          e);
    }

    if (!exists) {
      throw new BusinessException(
          "Provider series ID '" + providerSeriesId + "' does not exist in the external provider",
          CurrencyServiceError.INVALID_PROVIDER_SERIES_ID.name());
    }
  }
}
