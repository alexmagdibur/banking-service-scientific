package com.example.bankigservicescientific.transfer;

import java.util.List;
import java.util.UUID;

import com.example.bankigservicescientific.metrics.MeasuredOperation;
import com.example.bankigservicescientific.metrics.OperationType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    static final int MAX_KEY_LENGTH = 128;
    static final int MAX_LIMIT = 100;

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    @MeasuredOperation(OperationType.TRANSFER)
    public ResponseEntity<Transfer> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidTransferException("Idempotency-Key must be 1.." + MAX_KEY_LENGTH + " characters");
        }
        TransferResult result = service.transfer(idempotencyKey, request.fromAccountId(), request.toAccountId(),
                request.amountMinor());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.transfer());
    }

    @GetMapping("/{id}")
    public Transfer get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public List<Transfer> latest(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidTransferException("limit must be between 1 and " + MAX_LIMIT);
        }
        return service.latest(limit);
    }
}
