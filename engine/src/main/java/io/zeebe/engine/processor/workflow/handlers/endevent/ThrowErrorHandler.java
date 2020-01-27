/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.handlers.endevent;

import io.zeebe.engine.processor.workflow.BpmnStepContext;
import io.zeebe.engine.processor.workflow.deployment.model.element.ExecutableEndEvent;
import io.zeebe.engine.processor.workflow.handlers.element.ElementActivatedHandler;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.util.buffer.BufferUtil;

public final class ThrowErrorHandler extends ElementActivatedHandler<ExecutableEndEvent> {

  private final ErrorEventHandle errorEventHandle;

  public ThrowErrorHandler(final ErrorEventHandle errorEventHandle) {
    super(null);
    this.errorEventHandle = errorEventHandle;
  }

  @Override
  protected boolean handleState(final BpmnStepContext<ExecutableEndEvent> context) {
    if (!super.handleState(context)) {
      return false;
    }

    // TODO (saig0): throw error event

    final var endEvent = context.getElement();
    final var error = endEvent.getError();

    final var thrown =
        errorEventHandle.throwErrorEvent(
            error.getErrorCode(),
            context.getFlowScopeInstance(),
            context.getOutput().getStreamWriter());

    if (!thrown) {
      final var errorMessage =
          String.format(
              "An error was thrown with the code '%s' but not caught.",
              BufferUtil.bufferAsString(error.getErrorCode()));
      context.raiseIncident(ErrorType.UNHANDLED_ERROR_EVENT, errorMessage);
    }

    return thrown;
  }
}
