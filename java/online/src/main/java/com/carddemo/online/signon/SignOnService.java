package com.carddemo.online.signon;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.commarea.ScreenHeader;
import com.carddemo.online.files.FileResult;
import com.carddemo.online.files.OnlineFiles;
import com.carddemo.online.files.UserSecurity;
import org.springframework.stereotype.Service;

@Service
public class SignOnService {
    private final OnlineFiles files;
    private final FrozenClock clock;

    public SignOnService(OnlineFiles files, FrozenClock clock) {
        this.files = files;
        this.clock = clock;
    }

    public SignOnResponse initialScreen() {
        return response("", "USERID", null, null, "CC00", new CardDemoCommarea());
    }

    public SignOnResponse signOn(String aid, String userId, String password) {
        String action = aid == null || aid.isBlank() ? "ENTER" : aid.trim().toUpperCase();
        CardDemoCommarea commarea = new CardDemoCommarea();
        if ("PF3".equals(action) || "F3".equals(action)) {
            return response("", null, "Thank you for using CardDemo application...", null, null, commarea);
        }
        if (!"ENTER".equals(action)) {
            return response("Invalid key pressed. Please see below...", null, null, null, "CC00", commarea);
        }
        if (userId == null || userId.isBlank()) {
            return response("Please enter User ID ...", "USERID", null, null, "CC00", commarea);
        }
        if (password == null || password.isBlank()) {
            return response("Please enter Password ...", "PASSWD", null, null, "CC00", commarea);
        }
        String key = field(userId.toUpperCase(), 8);
        FileResult<UserSecurity> result = files.readUserSec(key);
        if (result.resp() == FileResult.NORMAL) {
            UserSecurity user = result.record();
            if (user != null && user.password().equals(field(password.toUpperCase(), 8))) {
                commarea.fromTranId = "CC00";
                commarea.fromProgram = "COSGN00C";
                commarea.userId = user.userId();
                commarea.userType = user.userType();
                commarea.pgmContext = 0;
                return response("", null, null,
                        "A".equals(user.userType()) ? "COADM01C" : "COMEN01C", null, commarea);
            }
            return response("Wrong Password. Try again ...", "PASSWD", null, null, "CC00", commarea);
        }
        if (result.resp() == FileResult.NOTFND) {
            return response("User not found. Try again ...", "USERID", null, null, "CC00", commarea);
        }
        return response("Unable to verify the User ...", "USERID", null, null, "CC00", commarea);
    }

    private SignOnResponse response(String message, String cursor, String plainText,
                                    String nextProgram, String returnTransId, CardDemoCommarea commarea) {
        return new SignOnResponse("COSGN0A",
                ScreenHeader.from(clock, ScreenHeader.TITLE_01, ScreenHeader.TITLE_02, "CC00", "COSGN00C"),
                rtrim(message), cursor, rtrim(plainText), nextProgram, returnTransId, commarea);
    }

    private static String field(String value, int width) {
        String result = value == null ? "" : value;
        return result.length() >= width ? result.substring(0, width)
                : result + " ".repeat(width - result.length());
    }

    private static String rtrim(String value) {
        return value == null ? null : value.replaceFirst("\\s+$", "");
    }

    public record SignOnResponse(String screen, ScreenHeader header, String errorMessage, String cursorField,
                                 String plainText, String nextProgram, String returnTransId,
                                 CardDemoCommarea commarea) {
    }
}
