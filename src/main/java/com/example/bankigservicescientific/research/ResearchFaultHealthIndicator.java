package com.example.bankigservicescientific.research;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** DOWN while the simulated unavailability is active; part of both the liveness and the readiness group. */
@Component("researchFault")
@Profile("research")
public class ResearchFaultHealthIndicator implements HealthIndicator {

    private final ResearchFaultState state;

    public ResearchFaultHealthIndicator(ResearchFaultState state) {
        this.state = state;
    }

    @Override
    public Health health() {
        return (state.isActive() ? Health.down() : Health.up()).build();
    }
}
