package com.example.bankigservicescientific.account;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String SELECT = "SELECT id, display_name, balance_minor FROM accounts";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> findAll() {
        return jdbc.sql(SELECT + " ORDER BY id")
                .query(Account.class)
                .list();
    }

    /**
     * Locks the given accounts with SELECT ... FOR UPDATE. Rows are locked in ascending ID order,
     * so two concurrent transfers over the same pair of accounts cannot deadlock.
     * Must be called inside a transaction.
     */
    public List<Account> lockByIdsOrdered(long firstId, long secondId) {
        return jdbc.sql(SELECT + " WHERE id IN (:first, :second) ORDER BY id FOR UPDATE")
                .param("first", firstId)
                .param("second", secondId)
                .query(Account.class)
                .list();
    }

    public void addToBalance(long id, long deltaMinor) {
        jdbc.sql("UPDATE accounts SET balance_minor = balance_minor + :delta WHERE id = :id")
                .param("delta", deltaMinor)
                .param("id", id)
                .update();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(Account.class)
                .optional();
    }
}
