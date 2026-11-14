package com.carddemo.online.signon;

import com.carddemo.online.Aid;
import com.carddemo.online.Commarea;
import com.carddemo.online.Messages;
import com.carddemo.online.Pic;
import com.carddemo.online.store.FileResult;
import com.carddemo.online.store.OnlineStore;
import com.carddemo.online.store.Resp;
import com.carddemo.online.store.UserSecurity;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** COSGN00C (transaction CC00, map COSGN0A of mapset COSGN00): CardDemo sign-on. */
@Service
public class SignOnService {

    static final String TRANID = "CC00";
    static final String PGMNAME = "COSGN00C";
    private final OnlineStore store;

    public SignOnService(OnlineStore store) {
        this.store = store;
    }

    /**
     * EIBCALEN = 0: send the blank sign-on map, cursor on USERID.
     *
     * @see "BR-ONL-001"
     */
    public SignOnResponse firstDisplay() {
        return new SignOnResponse("COSGN0A", "", "USERID", null, new Commarea(), false);
    }

    /**
     * One attention on the sign-on screen.
     *
     * @see "BR-ONL-002, BR-ONL-003, BR-ONL-004 .. BR-ONL-010"
     */
    public SignOnResponse process(String action, String userIdInput, String passwordInput) {
        Commarea ca = new Commarea();
        String aid = Aid.normalize(action);
        switch (aid) {
            case "ENTER":
                return processEnterKey(ca, userIdInput, passwordInput);
            case "PF3":
                // BR-ONL-002: SEND TEXT thank-you, RETURN without TRANSID
                return new SignOnResponse("PLAIN", Messages.THANK_YOU, null, null, ca, false);
            default:
                // BR-ONL-003
                return new SignOnResponse("COSGN0A", Messages.INVALID_KEY, "USERID", null, ca, false);
        }
    }

    private SignOnResponse processEnterKey(Commarea ca, String userIdIn, String pwdIn) {
        String userId = Pic.x(userIdIn, 8);
        String pwd = Pic.x(pwdIn, 8);
        // BR-ONL-004 / BR-ONL-005: EVALUATE TRUE, first WHEN wins
        if (Pic.isLowOrSpaces(userId)) {
            return new SignOnResponse("COSGN0A", "Please enter User ID ...", "USERID", null, ca, false);
        }
        if (Pic.isLowOrSpaces(pwd)) {
            return new SignOnResponse("COSGN0A", "Please enter Password ...", "PASSWD", null, ca, false);
        }
        // BR-ONL-006: FUNCTION UPPER-CASE of both inputs
        String wsUserId = userId.toUpperCase(Locale.ROOT);
        String wsUserPwd = pwd.toUpperCase(Locale.ROOT);
        ca.userId = wsUserId;
        return readUserSecFile(ca, wsUserId, wsUserPwd);
    }

    private SignOnResponse readUserSecFile(Commarea ca, String wsUserId, String wsUserPwd) {
        FileResult<UserSecurity> r = store.readUser(wsUserId);
        if (r.resp() == Resp.NORMAL) {
            UserSecurity u = r.record();
            if (Pic.x(u.password(), 8).equals(wsUserPwd)) {
                // BR-ONL-010
                ca.fromTranid = TRANID;
                ca.fromProgram = PGMNAME;
                ca.userId = wsUserId;
                ca.userType = u.userType();
                ca.pgmContext = 0;
                String next = "A".equals(u.userType()) ? "COADM01C" : "COMEN01C";
                ca.toProgram = next;
                return new SignOnResponse("COSGN0A", "", null, next, ca, true);
            }
            // BR-ONL-009 (F-11: WS-ERR-FLG not set; no visible effect)
            return new SignOnResponse("COSGN0A", "Wrong Password. Try again ...", "PASSWD", null, ca, true);
        }
        if (r.resp() == Resp.NOTFND) {
            // BR-ONL-007
            return new SignOnResponse("COSGN0A", "User not found. Try again ...", "USERID", null, ca, true);
        }
        // BR-ONL-008
        return new SignOnResponse("COSGN0A", "Unable to verify the User ...", "USERID", null, ca, true);
    }
}
