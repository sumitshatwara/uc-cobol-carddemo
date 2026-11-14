package com.carddemo.online;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.carddemo.online.account.AccountViewResponse;
import com.carddemo.online.account.AccountViewService;
import com.carddemo.online.store.CardXref;
import com.carddemo.online.store.InMemoryOnlineStore;
import com.carddemo.online.store.InMemoryOnlineStore.Op;
import com.carddemo.online.store.Resp;
import org.junit.jupiter.api.Test;

class AccountViewServiceTest {

    private final InMemoryOnlineStore store = Fixtures.store();
    private final AccountViewService svc = new AccountViewService(store);

    private static Commarea reenter() {
        Commarea ca = new Commarea();
        ca.fromProgram = "COMEN01C";
        ca.fromTranid = "CM00";
        ca.userType = "A";
        ca.pgmContext = 1;
        return ca;
    }

    @Test
    void BR_ONL_020_freshEntryAndPfKeyMapping() {
        Commarea fromMenu = reenter();
        fromMenu.pgmContext = 0;
        AccountViewResponse fresh = svc.process(fromMenu, "ENTER", "");
        assertEquals("Enter or update id of account to display", fresh.infoMessage());
        assertNull(fresh.account());
        // PF8 acts as ENTER: the id is re-edited and re-read
        AccountViewResponse pf8 = svc.process(reenter(), "PF8", "00000000099");
        assertEquals("AVERY", pf8.customer().get("firstName"));
        // PF15 folds to PF3
        assertEquals("COMEN01C", svc.process(reenter(), "PF15", "00000000099").next());
    }

    @Test
    void BR_ONL_021_pf3ReturnsToCallerAsUserType_U() {
        AccountViewResponse r = svc.process(reenter(), "PF3", "");
        assertEquals("COMEN01C", r.next());
        assertEquals("COACTVWC", r.commarea().fromProgram);
        assertEquals("CAVW", r.commarea().fromTranid);
        assertEquals("U", r.commarea().userType); // F-02: admin downgraded
        assertEquals(0, r.commarea().pgmContext);
        Commarea none = reenter();
        none.fromProgram = "";
        none.fromTranid = "";
        assertEquals("COMEN01C", svc.process(none, "PF3", "").next());
        assertEquals("CM00", svc.process(none, "PF3", "").commarea().toTranid);
    }

    @Test
    void BR_ONL_022_blankOrStarSearchKey() {
        for (String in : new String[] {"", "*"}) {
            AccountViewResponse r = svc.process(reenter(), "ENTER", in);
            assertEquals("No input received", r.errorMessage());
            assertEquals("*", r.acctIdField());
            assertEquals("RED", r.acctIdColor());
            assertNull(r.account());
        }
    }

    @Test
    void BR_ONL_023_keyMustBeNonZero11Digits() {
        for (String in : new String[] {"1234", "00000000000", "ABC00000001"}) {
            AccountViewResponse r = svc.process(reenter(), "ENTER", in);
            assertEquals("Account Filter must  be a non-zero 11 digit number", r.errorMessage(), in);
            assertEquals("RED", r.acctIdColor());
            assertEquals(0, r.commarea().acctId);
        }
    }

    @Test
    void BR_ONL_024_lookupChainXrefAccountCustomer() {
        AccountViewResponse r = svc.process(reenter(), "ENTER", "00000000099");
        assertEquals("", r.errorMessage());
        assertEquals(99, r.custId());
        assertEquals("4000000000000099", r.cardNum());
        assertEquals("STANDARD", r.account().get("groupId"));
        assertEquals("SAMPLE", r.customer().get("lastName"));
    }

    @Test
    void BR_ONL_025_notFoundMessagesTruncatedTo75() {
        AccountViewResponse x = svc.process(reenter(), "ENTER", "00000000077");
        assertEquals("Account:00000000077 not found in Cross ref file.  Resp:000000013  Reas:0000", x.errorMessage());
        assertEquals(75, ("Account:00000000077 not found in Cross ref file.  Resp:000000013  Reas:0000").length());
        // xref present, account and customer missing: first message kept
        store.xrefByAcct.put(55L, new CardXref("4000000000000055", 55, 55));
        AccountViewResponse a = svc.process(reenter(), "ENTER", "00000000055");
        assertEquals("Account:00000000055 not found in Acct Master file.Resp:000000013  Reas:0000",
                a.errorMessage());
        // account present, customer missing
        store.xrefByAcct.put(99L, new CardXref("4000000000000099", 98, 99));
        AccountViewResponse c = svc.process(reenter(), "ENTER", "00000000099");
        assertEquals("CustId:000000098 not found in customer master.Resp: 000000013  REAS:0000000",
                c.errorMessage());
    }

    @Test
    void BR_ONL_026_fileErrorMessage() {
        store.fail(Op.READ_ACCT, Resp.DISABLED, 0);
        AccountViewResponse r = svc.process(reenter(), "ENTER", "00000000099");
        assertEquals("File Error: READ     on ACCTDAT   returned RESP 000000084 ,RESP2 000000000", r.errorMessage());
    }

    @Test
    void BR_ONL_027_displayFormatting() {
        AccountViewResponse r = svc.process(reenter(), "ENTER", "00000000099");
        assertEquals("123-45-6789", r.customer().get("ssn"));
        assertEquals("Enter or update id of account to display", r.infoMessage());
        assertEquals("+      2,000.00", r.account().get("creditLimit"));
        // F-01: customer found but account missing still shows the (empty) account block
        store.accounts.remove(99L);
        AccountViewResponse noAcct = svc.process(reenter(), "ENTER", "00000000099");
        assertNotNull(noAcct.account());
        assertNotNull(noAcct.customer());
        // customer missing: no customer block
        store.customers.remove(99L);
        assertNull(svc.process(reenter(), "ENTER", "00000000099").customer());
    }
}
