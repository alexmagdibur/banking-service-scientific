package com.example.bankigservicescientific.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String SELECT = """
            SELECT id, from_account_id, to_account_id, amount_minor, status, created_at, request_fingerprint
            FROM transfers""";

    private static final RowMapper<StoredTransfer> MAPPER = TransferRepository::map;

    /** A transfer together with the fingerprint of the request that created it. */
    public record StoredTransfer(Transfer transfer, String requestFingerprint) {
    }

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Transfer transfer, String idempotencyKey, String requestFingerprint) {
        jdbc.sql("""
                        INSERT INTO transfers (id, idempotency_key, from_account_id, to_account_id, amount_minor,
                                               request_fingerprint, status, created_at)
                        VALUES (:id, :key, :from, :to, :amount, :fingerprint, :status, :createdAt)
                        """)
                .param("id", transfer.id())
                .param("key", idempotencyKey)
                .param("from", transfer.fromAccountId())
                .param("to", transfer.toAccountId())
                .param("amount", transfer.amountMinor())
                .param("fingerprint", requestFingerprint)
                .param("status", transfer.status())
                .param("createdAt", OffsetDateTime.ofInstant(transfer.createdAt(), ZoneOffset.UTC))
                .update();
    }

    public Optional<StoredTransfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT + " WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(MAPPER)
                .optional();
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional()
                .map(StoredTransfer::transfer);
    }

    public List<Transfer> findLatest(int limit) {
        return jdbc.sql(SELECT + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("limit", limit)
                .query(MAPPER)
                .list()
                .stream()
                .map(StoredTransfer::transfer)
                .toList();
    }

    /** Serialises concurrent requests with the same key until the surrounding transaction ends. */
    public void lockIdempotencyKey(String idempotencyKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", idempotencyKey)
                .query()
                .singleValue();
    }

    private static StoredTransfer map(ResultSet rs, int rowNum) throws SQLException {
        Transfer transfer = new Transfer(
                rs.getObject("id", UUID.class),
                rs.getLong("from_account_id"),
                rs.getLong("to_account_id"),
                rs.getLong("amount_minor"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
        return new StoredTransfer(transfer, rs.getString("request_fingerprint"));
    }
}
