package com.example.bankigservicescientific.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Counts and times {@link MeasuredOperation} requests as {@code bank.operations{operation, outcome}}.
 * The outcome is taken from the final HTTP status, so validation errors raised before the controller body
 * runs are counted too: 2xx = success, 4xx = business_error, 5xx (or an unhandled exception) = technical_error.
 * Client-side timeouts are invisible to the server and are counted only by the load generator.
 */
@Component
public class OperationMetricsInterceptor implements HandlerInterceptor {

    static final String METRIC = "bank.operations";
    private static final String START_ATTRIBUTE = OperationMetricsInterceptor.class.getName() + ".start";
    private static final Logger log = LoggerFactory.getLogger("bank.operations");

    public enum Outcome {
        SUCCESS("success"), BUSINESS_ERROR("business_error"), TECHNICAL_ERROR("technical_error");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }

    private final Map<OperationType, Map<Outcome, Timer>> timers = new EnumMap<>(OperationType.class);

    public OperationMetricsInterceptor(MeterRegistry registry) {
        // Registered up front so that every series exists (with 0) from the first scrape.
        for (OperationType operation : OperationType.values()) {
            Map<Outcome, Timer> byOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                byOutcome.put(outcome, Timer.builder(METRIC)
                        .description("Business operations handled by this instance")
                        .tag("operation", operation.label())
                        .tag("outcome", outcome.label)
                        .register(registry));
            }
            timers.put(operation, byOutcome);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (operationOf(handler) != null) {
            request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        OperationType operation = operationOf(handler);
        Object start = request.getAttribute(START_ATTRIBUTE);
        if (operation == null || !(start instanceof Long startNanos)) {
            return;
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        int status = response.getStatus();
        Outcome outcome = classify(status, exception);
        timers.get(operation).get(outcome).record(duration);
        log.info("op={} outcome={} status={} duration_ms={}", operation.label(), outcome.label, status,
                duration.toNanos() / 1_000_000.0);
    }

    static Outcome classify(int status, Exception exception) {
        if (exception != null || status >= 500) {
            return Outcome.TECHNICAL_ERROR;
        }
        return status >= 400 ? Outcome.BUSINESS_ERROR : Outcome.SUCCESS;
    }

    private static OperationType operationOf(Object handler) {
        if (handler instanceof HandlerMethod method) {
            MeasuredOperation annotation = method.getMethodAnnotation(MeasuredOperation.class);
            return annotation == null ? null : annotation.value();
        }
        return null;
    }
}
