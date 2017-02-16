package it.ratelim.client;

import java.io.IOException;

public class RateLimitException extends RuntimeException {
  public RateLimitException(Exception e) {
    super(e);
  }
}
