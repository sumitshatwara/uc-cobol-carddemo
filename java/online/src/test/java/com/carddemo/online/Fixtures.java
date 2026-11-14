package com.carddemo.online;

import com.carddemo.online.store.AccountRecord;
import com.carddemo.online.store.CardXref;
import com.carddemo.online.store.CustomerRecord;
import com.carddemo.online.store.InMemoryOnlineStore;
import com.carddemo.online.store.UserSecurity;
import java.math.BigDecimal;

/** Synthetic in-memory data for the unit tests (no database). */
public final class Fixtures {
    private Fixtures() {}

    public static AccountRecord account99() {
        return new AccountRecord(99, "Y", new BigDecimal("150.00"), new BigDecimal("2000.00"), new BigDecimal("500.00"),
                "2019-03-15", "2027-03-31", "2023-03-15", new BigDecimal("1500.00"), new BigDecimal("265.44"),
                "62701     ", "STANDARD  ");
    }

    public static CustomerRecord customer99() {
        return new CustomerRecord(99, Pic.x("AVERY", 25), Pic.x("Q", 25), Pic.x("SAMPLE", 25),
                Pic.x("100 TEST STREET", 50), Pic.x("UNIT 1", 50), Pic.x("SPRINGFIELD", 50), "IL", "USA",
                Pic.x("62701", 10), Pic.x("(217)555-0142", 15), Pic.x("(217)555-0143", 15), 123456789L,
                Pic.x("TESTID-0099", 20), "1985-06-15", "0000099001", "Y", 720);
    }

    public static InMemoryOnlineStore store() {
        InMemoryOnlineStore s = new InMemoryOnlineStore();
        s.users.put("ADMIN001", new UserSecurity("ADMIN001", "DEMO", "ADMIN", "PASSWORD", "A"));
        s.users.put("USER0001", new UserSecurity("USER0001", "DEMO", "OPERATOR", "PASSW0RD", "U"));
        s.xrefByAcct.put(99L, new CardXref("4000000000000099", 99, 99));
        s.accounts.put(99L, account99());
        s.customers.put(99L, customer99());
        return s;
    }
}
