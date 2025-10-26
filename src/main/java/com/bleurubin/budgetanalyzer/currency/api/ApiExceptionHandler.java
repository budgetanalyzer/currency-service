package com.bleurubin.budgetanalyzer.currency.api;

import com.bleurubin.budgetanalyzer.currency.api.response.ApiErrorResponse;
import java.time.Instant;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class ApiExceptionHandler {

  private final Logger log = LoggerFactory.getLogger(this.getClass());

  @ExceptionHandler
  @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiErrorResponse handle(Exception exception, WebRequest request) {
    return handleApiException(
        "internal_server_error", HttpStatus.INTERNAL_SERVER_ERROR, request, exception);
  }

  private ApiErrorResponse handleApiException(
      String type, HttpStatus httpStatus, WebRequest request, Throwable throwable) {
    var message = throwable.getMessage();
    var rootCause = ExceptionUtils.getRootCause(throwable);
    if (rootCause != null) {
      log.warn(
          "Handled exception: {} root cause: {} message: {}",
          throwable.getClass(),
          rootCause.getClass(),
          message,
          throwable);
    } else {
      log.warn("Handled exception: {} message: {}", throwable.getClass(), message, throwable);
    }

    return ApiErrorResponse.builder()
        .type(type)
        .title(type)
        .status(httpStatus.value())
        .detail(throwable.getMessage())
        .instance(extractUri(request))
        .timestamp(Instant.now())
        .build();
  }

  private String extractUri(WebRequest request) {
    return request.getDescription(false).replace("uri=", "");
  }
}
