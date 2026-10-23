package com.example.bankigservicescientific.research;

import java.time.Instant;

import com.example.bankigservicescientific.instance.InstanceId;
import org.springframework.boot.actuate.endpoint.InvalidEndpointRequestException;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * POST /actuator/researchfault?mode=UNRESPONSIVE&amp;durationSeconds=N on the internal management port (8081),
 * to be called through port-forward to one chosen Pod. It exists only in the "research" profile.
 */
@Component
@Profile("research")
@Endpoint(id = "researchfault")
public class ResearchFaultEndpoint {

    static final String MODE_UNRESPONSIVE = "UNRESPONSIVE";
    static final int MIN_SECONDS = 1;
    static final int MAX_SECONDS = 120;

    public record Confirmation(String instanceId, String mode, int durationSeconds, Instant startsAt, Instant endsAt,
                               String message) {
    }

    public record Status(String instanceId, boolean active, long remainingSeconds) {
    }

    private final ResearchFaultState state;
    private final InstanceId instanceId;

    public ResearchFaultEndpoint(ResearchFaultState state, InstanceId instanceId) {
        this.state = state;
        this.instanceId = instanceId;
    }

    @WriteOperation
    public Confirmation activate(String mode, Integer durationSeconds) {
        if (!MODE_UNRESPONSIVE.equals(mode)) {
            throw new InvalidEndpointRequestException("mode must be " + MODE_UNRESPONSIVE, "Unsupported mode");
        }
        if (durationSeconds < MIN_SECONDS || durationSeconds > MAX_SECONDS) {
            throw new InvalidEndpointRequestException(
                    "durationSeconds must be between " + MIN_SECONDS + " and " + MAX_SECONDS, "Invalid duration");
        }
        ResearchFaultState.Activation activation = state.activate(durationSeconds, instanceId.value());
        return new Confirmation(instanceId.value(), MODE_UNRESPONSIVE, durationSeconds, activation.startsAt(),
                activation.endsAt(), "Simulated unavailability of this instance (not a real node or process failure)");
    }

    @ReadOperation
    public Status status() {
        return new Status(instanceId.value(), state.isActive(), state.remainingSeconds());
    }

    @DeleteOperation
    public Status clear() {
        state.clear(instanceId.value());
        return status();
    }
}
