package com.example.bankigservicescientific.instance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Identifier of this application instance (the Pod name in Kubernetes). */
@Component
public class InstanceId {

    private static final Logger log = LoggerFactory.getLogger(InstanceId.class);

    private final String value;

    public InstanceId(@Value("${bank.instance-id}") String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logReady() {
        log.info("Instance {} is ready", value);
    }
}
