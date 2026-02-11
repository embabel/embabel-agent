/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.observability.observation;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.observability.annotation.Tracked;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.StringJoiner;

/**
 * Aspect that intercepts methods annotated with {@link Tracked} and creates
 * observability spans capturing inputs, outputs, duration, and errors.
 * When called within an agent process, the span is enriched with
 * runId and agent name.
 *
 * @since 0.3.4
 */
@Aspect
public class TrackedAspect {

    private static final Logger log = LoggerFactory.getLogger(TrackedAspect.class);

    private final ObservationRegistry registry;
    private final int maxAttributeLength;

    public TrackedAspect(ObservationRegistry registry, int maxAttributeLength) {
        this.registry = registry;
        this.maxAttributeLength = maxAttributeLength;
    }

    /**
     * Intercepts {@link Tracked}-annotated methods and wraps them in an observation.
     *
     * @param joinPoint the join point
     * @param tracked   the annotation instance
     * @return the method return value
     * @throws Throwable if the method throws
     */
    @Around("@annotation(tracked)")
    public Object tracked(ProceedingJoinPoint joinPoint, Tracked tracked) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String operationName = tracked.value().isEmpty()
                ? signature.getMethod().getName()
                : tracked.value();

        // Resolve runId from current AgentProcess if available
        String runId = "";
        AgentProcess process = AgentProcess.get();
        if (process != null) {
            runId = process.getId();
        }

        EmbabelObservationContext context = EmbabelObservationContext.custom(runId, operationName);
        Observation observation = Observation.createNotStarted(operationName, () -> context, registry);

        // Low cardinality tags
        observation.lowCardinalityKeyValue("embabel.tracked.type", tracked.type().name());
        observation.lowCardinalityKeyValue("embabel.tracked.class", signature.getDeclaringType().getSimpleName());
        if (!tracked.description().isEmpty()) {
            observation.lowCardinalityKeyValue("embabel.tracked.description", tracked.description());
        }
        if (process != null) {
            observation.lowCardinalityKeyValue("embabel.tracked.agent", process.getAgent().getName());
        }

        // High cardinality tags (inputs)
        String argsString = truncate(formatArgs(signature.getParameterNames(), joinPoint.getArgs()));
        observation.highCardinalityKeyValue("embabel.tracked.args", argsString);

        observation.start();
        Observation.Scope scope = observation.openScope();
        try {
            Object result = joinPoint.proceed();
            if (result != null) {
                observation.highCardinalityKeyValue("embabel.tracked.result", truncate(result.toString()));
            }
            return result;
        } catch (Throwable t) {
            observation.error(t);
            throw t;
        } finally {
            scope.close();
            observation.stop();
        }
    }

    private String formatArgs(String[] paramNames, Object[] args) {
        if (paramNames == null || paramNames.length != args.length) {
            return Arrays.toString(args);
        }
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (int i = 0; i < paramNames.length; i++) {
            joiner.add(paramNames[i] + "=" + args[i]);
        }
        return joiner.toString();
    }

    private String truncate(String value) {
        return ObservationUtils.truncate(value, maxAttributeLength);
    }
}
