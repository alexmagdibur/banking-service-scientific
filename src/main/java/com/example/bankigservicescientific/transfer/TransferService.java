package com.example.bankigservicescientific.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.bankigservicescientific.account.Account;
import com.example.bankigservicescientific.account.AccountNotFoundException;
import com.example.bankigservicescientific.account.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    public static final long MIN_AMOUNT_MINOR = 1;
    public static final long MAX_AMOUNT_MINOR = 1_000_000;
    static final String STATUS_COMPLETED = "COMPLETED";

    private final AccountRepository accounts;
    private final TransferRepository transfers;

    public TransferService(AccountRepository accounts, TransferRepository transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    /**
     * Debit, credit and the transfer record happen in one database transaction: any exception
     * thrown here (including a failed INSERT after the balances were updated) rolls everything back.
     * <p>
     * Idempotency: requests with the same key are serialised by a transaction-scoped advisory lock, so the
     * lookup below sees every committed transfer with that key. The same key with the same request returns
     * the stored transfer without touching balances; with a different request it is a conflict. The UNIQUE
     * constraint on idempotency_key remains as the last line of defence.
     */
    @Transactional
    public TransferResult transfer(String idempotencyKey, long fromId, long toId, long amountMinor) {
        if (fromId == toId) {
            throw new InvalidTransferException("Sender and recipient accounts must differ");
        }
        if (amountMinor < MIN_AMOUNT_MINOR || amountMinor > MAX_AMOUNT_MINOR) {
            throw new InvalidTransferException("Amount must be between " + MIN_AMOUNT_MINOR + " and "
                    + MAX_AMOUNT_MINOR + " kopecks");
        }

        String fingerprint = fingerprint(fromId, toId, amountMinor);
        transfers.lockIdempotencyKey(idempotencyKey);
        Optional<TransferRepository.StoredTransfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            return new TransferResult(existing.get().transfer(), false);
        }

        List<Account> locked = accounts.lockByIdsOrdered(Math.min(fromId, toId), Math.max(fromId, toId));
        Account from = find(locked, fromId);
        find(locked, toId);

        if (from.balanceMinor() < amountMinor) {
            throw new InsufficientFundsException(fromId);
        }

        accounts.addToBalance(fromId, -amountMinor);
        accounts.addToBalance(toId, amountMinor);

        Transfer transfer = new Transfer(UUID.randomUUID(), fromId, toId, amountMinor, STATUS_COMPLETED,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        transfers.insert(transfer, idempotencyKey, fingerprint);
        return new TransferResult(transfer, true);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Transfer> latest(int limit) {
        return transfers.findLatest(limit);
    }

    private static Account find(List<Account> locked, long id) {
        return locked.stream()
                .filter(a -> a.id() == id)
                .findFirst()
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    static String fingerprint(long fromId, long toId, long amountMinor) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((fromId + "|" + toId + "|" + amountMinor).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
