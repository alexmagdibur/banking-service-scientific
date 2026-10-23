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

    public Optional<Account> findById(long id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(Account.class)
                .optional();
    }
}
