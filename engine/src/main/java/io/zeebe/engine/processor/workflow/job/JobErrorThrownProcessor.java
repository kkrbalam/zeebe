/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.job;

import static io.zeebe.util.buffer.BufferUtil.wrapString;

import io.zeebe.engine.processor.KeyGenerator;
import io.zeebe.engine.processor.TypedRecord;
import io.zeebe.engine.processor.TypedRecordProcessor;
import io.zeebe.engine.processor.TypedResponseWriter;
import io.zeebe.engine.processor.TypedStreamWriter;
import io.zeebe.engine.processor.workflow.handlers.endevent.ErrorEventHandle;
import io.zeebe.engine.state.deployment.WorkflowState;
import io.zeebe.engine.state.instance.ElementInstanceState;
import io.zeebe.engine.state.instance.JobState;
import io.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.zeebe.protocol.impl.record.value.job.JobRecord;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.value.ErrorType;
import org.agrona.DirectBuffer;

public class JobErrorThrownProcessor implements TypedRecordProcessor<JobRecord> {

  private final IncidentRecord incidentEvent = new IncidentRecord();

  private final ElementInstanceState elementInstanceState;
  private final JobState jobState;
  private final ErrorEventHandle errorEventHandle;

  public JobErrorThrownProcessor(
      final WorkflowState workflowState, final KeyGenerator keyGenerator, final JobState jobState) {
    elementInstanceState = workflowState.getElementInstanceState();
    this.jobState = jobState;
    errorEventHandle = new ErrorEventHandle(workflowState, keyGenerator);
  }

  @Override
  public void processRecord(
      final TypedRecord<JobRecord> record,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter) {

    final var job = record.getValue();
    final var serviceTaskInstanceKey = job.getElementInstanceKey();
    final var serviceTaskInstance = elementInstanceState.getInstance(serviceTaskInstanceKey);

    if (serviceTaskInstance != null && serviceTaskInstance.isActive()) {

      final var errorCode = job.getErrorCodeBuffer();

      final boolean errorThrown =
          errorEventHandle.throwErrorEvent(errorCode, serviceTaskInstance, streamWriter);
      if (errorThrown) {

        // remove job reference to not cancel it while terminating the task
        serviceTaskInstance.setJobKey(-1L);
        elementInstanceState.updateInstance(serviceTaskInstance);

        // remove job from state
        jobState.throwError(record.getKey(), job);

      } else {
        // mark job as failed and create an incident
        job.setRetries(0);
        jobState.fail(record.getKey(), job);

        raiseIncident(record.getKey(), job, streamWriter);
      }
    }
  }

  private void raiseIncident(
      final long key, final JobRecord job, final TypedStreamWriter streamWriter) {

    final DirectBuffer jobErrorMessage = job.getErrorMessageBuffer();
    DirectBuffer incidentErrorMessage =
        wrapString(
            String.format(
                "An error was thrown with the code '%s' but not caught.", job.getErrorCode()));
    if (jobErrorMessage.capacity() > 0) {
      incidentErrorMessage = jobErrorMessage;
    }

    incidentEvent.reset();
    incidentEvent
        .setErrorType(ErrorType.JOB_NO_RETRIES)
        .setErrorMessage(incidentErrorMessage)
        .setBpmnProcessId(job.getBpmnProcessIdBuffer())
        .setWorkflowKey(job.getWorkflowKey())
        .setWorkflowInstanceKey(job.getWorkflowInstanceKey())
        .setElementId(job.getElementIdBuffer())
        .setElementInstanceKey(job.getElementInstanceKey())
        .setJobKey(key)
        .setVariableScopeKey(job.getElementInstanceKey());

    streamWriter.appendNewCommand(IncidentIntent.CREATE, incidentEvent);
  }
}
