package de.sikeller.aqs.model;

import lombok.Getter;

public class ErrorHandlerFactory {
  @Getter private static final ErrorHandler instance = new ErrorHandler(true);
}
