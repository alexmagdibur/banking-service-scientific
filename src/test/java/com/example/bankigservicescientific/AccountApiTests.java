package com.example.bankigservicescientific;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Needs the test PostgreSQL configured via BANK_DB_*; checks only read-only behaviour that does not depend on balances. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountApiTests {

    @Autowired
    MockMvc mvc;

    @Test
    void listReturnsAccountsOrderedById() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1001))
                .andExpect(jsonPath("$[1].id").value(1002))
                .andExpect(jsonPath("$[2].id").value(1003))
                .andExpect(jsonPath("$[0].displayName").value("Счёт А"))
                .andExpect(jsonPath("$[0].balanceMinor").isNumber());
    }

    @Test
    void getReturnsSingleAccount() throws Exception {
        mvc.perform(get("/api/accounts/1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1002))
                .andExpect(jsonPath("$.displayName").value("Счёт Б"));
    }

    @Test
    void unknownAccountGives404WithErrorBody() throws Exception {
        mvc.perform(get("/api/accounts/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void malformedIdGives400() throws Exception {
        mvc.perform(get("/api/accounts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unknownPathGives404WithErrorBody() throws Exception {
        mvc.perform(get("/api/nothing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
