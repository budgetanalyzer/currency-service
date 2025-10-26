package com.bleurubin.budgetanalyzer.currency.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for converting objects to JSON strings. Thread-safe and efficient: reuses a single
 * ObjectMapper instance.
 */
public final class JsonUtils {

  private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);
  // Reusable ObjectMapper instance
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.registerModule(new JavaTimeModule());

    // write dates as ISO strings, not timestamps
    MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    MAPPER.configure(SerializationFeature.INDENT_OUTPUT, true);
  }

  // Private constructor to prevent instantiation
  private JsonUtils() {}

  /**
   * Converts an object to its JSON string representation. Returns "{}" if serialization fails.
   *
   * @param obj the object to serialize
   * @return JSON string representation
   */
  public static String toJson(Object obj) {
    if (obj == null) {
      return "null";
    }
    try {
      return MAPPER.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      // Optionally log the exception
      log.warn("Failed to serialize object to JSON", e);
      return "{}";
    }
  }
}
