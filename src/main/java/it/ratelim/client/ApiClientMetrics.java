package it.ratelim.client;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ApiClientMetrics {

  public enum METRICS {
    IT_RATELIM_LIMIT_CHECK_PASS,
    IT_RATELIM_LIMIT_CHECK_HIT
  }

  private Map<METRICS, Meter> meters = new HashMap<>();

  public ApiClientMetrics(Optional<MetricRegistry> metricRegistry) {
    if (metricRegistry.isPresent()) {
      for (METRICS metric : METRICS.values()) {
        meters.put(metric, metricRegistry.get().meter(metric.name().toLowerCase().replaceAll("_", ".")));
      }
    }
  }

  public void mark(METRICS metric) {
    final Meter meter = meters.get(metric);
    if(null != meter){
      meter.mark();
    }
  }
}
