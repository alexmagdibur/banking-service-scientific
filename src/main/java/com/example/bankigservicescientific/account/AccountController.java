package com.example.bankigservicescientific.account;

import java.util.List;

import com.example.bankigservicescientific.metrics.MeasuredOperation;
import com.example.bankigservicescientific.metrics.OperationType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository accounts;

    public AccountController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @MeasuredOperation(OperationType.BALANCE_VIEW)
    public List<Account> list() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    @MeasuredOperation(OperationType.BALANCE_VIEW)
    public Account get(@PathVariable long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
