package com.example.bankigservicescientific.instance;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds X-Instance-Id to every response so that a client can tell which Pod served it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Instance-Id";

    private final InstanceId instanceId;

    public InstanceIdFilter(InstanceId instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceId.value());
        chain.doFilter(request, response);
    }
}
