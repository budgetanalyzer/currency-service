package com.bleurubin.budgetanalyzer.currency.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CsvData {

  private List<Map<String, String>> rows = new ArrayList<>();

  public CsvData() {}

  public List<Map<String, String>> getRows() {
    return rows;
  }

  public void setRows(List<Map<String, String>> rows) {
    this.rows = rows;
  }
}
