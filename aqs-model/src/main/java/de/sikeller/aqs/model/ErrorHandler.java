package de.sikeller.aqs.model;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ErrorHandler {
  private final boolean throwError;

  public void error(String message, Object... vars) {
    var msg = message.formatted(vars);
    if (throwError) {
      throw new IllegalStateException(msg);
    } else {
      log.error(msg);
    }
  }
}
