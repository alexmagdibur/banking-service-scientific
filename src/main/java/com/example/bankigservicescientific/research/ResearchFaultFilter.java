package com.example.bankigservicescientific.research;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.example.bankigservicescientific.instance.InstanceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While the window is active every /api/** request of this instance hangs (no response, so a client with a
 * timeout gives up). The request is never passed on, so no business code runs and no bank data can change:
 * when the window ends, a request that is still waiting is answered with 503 and is NOT executed.
 */
@Component
@Profile("research")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ResearchFaultFilter extends OncePerRequestFilter {

    private static final long POLL_MILLIS = 100;

    private final ResearchFaultState state;
    private final InstanceId instanceId;

    public ResearchFaultFilter(ResearchFaultState state, InstanceId instanceId) {
        this.state = state;
        this.instanceId = instanceId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!state.isActive()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            while (state.isActive()) {
                TimeUnit.MILLISECONDS.sleep(Math.min(POLL_MILLIS, Math.max(1, state.remainingNanos() / 1_000_000)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"INSTANCE_UNRESPONSIVE\",\"message\":\"Instance " + instanceId.value()
                + " was unresponsive; the request was not processed\",\"timestamp\":\"" + Instant.now() + "\"}");
    }
}
