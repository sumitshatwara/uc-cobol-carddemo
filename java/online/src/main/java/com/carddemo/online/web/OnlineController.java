package com.carddemo.online.web;

import com.carddemo.online.Commarea;
import com.carddemo.online.account.AccountUpdateService;
import com.carddemo.online.account.AccountViewResponse;
import com.carddemo.online.account.AccountViewService;
import com.carddemo.online.account.AcupDetails;
import com.carddemo.online.account.UpdateRequest;
import com.carddemo.online.account.UpdateResponse;
import com.carddemo.online.menu.MenuResponse;
import com.carddemo.online.menu.MenuService;
import com.carddemo.online.routines.DateFormatRoutine;
import com.carddemo.online.routines.FeeDriver;
import com.carddemo.online.routines.FeeRequest;
import com.carddemo.online.routines.FeeResult;
import com.carddemo.online.routines.LateFeeCalculator;
import com.carddemo.online.signon.SignOnResponse;
import com.carddemo.online.signon.SignOnService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST face of the online slice.  Each call is one pseudo-conversational interaction: the client
 * carries the commarea and the update screen's state/snapshot between calls.  REST calls are
 * re-entries (CDEMO-PGM-REENTER); the empty map a fresh XCTL sends is the console screen load.
 */
@RestController
@RequestMapping("/api/online")
public class OnlineController {

    private final SignOnService signOn;
    private final MenuService menu;
    private final AccountViewService view;
    private final AccountUpdateService update;
    private final LateFeeCalculator fees;
    private final FeeDriver feeDriver;
    private final DateFormatRoutine dates;

    public OnlineController(SignOnService signOn, MenuService menu, AccountViewService view,
                            AccountUpdateService update, LateFeeCalculator fees, FeeDriver feeDriver,
                            DateFormatRoutine dates) {
        this.signOn = signOn;
        this.menu = menu;
        this.view = view;
        this.update = update;
        this.fees = fees;
        this.feeDriver = feeDriver;
        this.dates = dates;
    }

    public record SignOnBody(String action, String userId, String password) {}

    public record MenuSelectBody(String action, String option, String userType, Commarea commarea) {}

    public record ChangesBody(String action, String state, AcupDetails snapshot, Map<String, String> fields,
                              Commarea commarea) {}

    public record FeeBody(String acctId, String groupId, BigDecimal cycleBal, Integer lateDays, Integer cycleDays) {}

    public record DateBody(String inType, String inDate, String outType, String outDate) {}

    /** COSGN00C. */
    @PostMapping("/signon")
    public SignOnResponse signon(@RequestBody SignOnBody b) {
        return signOn.process(b.action(), b.userId(), b.password());
    }

    /** COMEN01C first display. */
    @GetMapping("/menu")
    public MenuResponse menu(@RequestParam(defaultValue = "U") String userType) {
        Commarea ca = new Commarea();
        ca.userType = userType;
        ca.fromProgram = "COSGN00C";
        ca.fromTranid = "CC00";
        return menu.display(ca);
    }

    /** COMEN01C re-enter. */
    @PostMapping("/menu/select")
    public MenuResponse select(@RequestBody MenuSelectBody b) {
        Commarea ca = b.commarea() == null ? new Commarea() : b.commarea();
        if (b.userType() != null && !b.userType().isBlank()) {
            ca.userType = b.userType();
        }
        ca.pgmContext = 1;
        return menu.select(ca, b.action(), b.option());
    }

    /** COACTVWC re-enter with the account id typed in ACCTSID. */
    @GetMapping("/accounts/{acctId}")
    public AccountViewResponse account(@PathVariable String acctId,
                                       @RequestParam(defaultValue = "ENTER") String action) {
        Commarea ca = new Commarea();
        ca.fromProgram = "COMEN01C";
        ca.fromTranid = "CM00";
        ca.pgmContext = 1;
        return view.process(ca, action, acctId);
    }

    private static Commarea reenter(Commarea in) {
        Commarea ca = in == null ? new Commarea() : in.copy();
        if (ca.fromProgram == null || ca.fromProgram.isBlank()) {
            ca.fromProgram = "COMEN01C";
            ca.fromTranid = "CM00";
        }
        ca.pgmContext = 1;
        return ca;
    }

    /** COACTUPC: search, edits (ENTER), cancel (PF12), exit (PF3). */
    @PostMapping("/accounts/{acctId}/changes")
    @Transactional
    public UpdateResponse changes(@PathVariable String acctId, @RequestBody ChangesBody b) {
        return update.process(new UpdateRequest(b.action(), b.state(), acctId, b.snapshot(), b.fields(),
                reenter(b.commarea())));
    }

    /** COACTUPC PF5: save with the optimistic concurrency check. */
    @PutMapping("/accounts/{acctId}")
    @Transactional
    public UpdateResponse save(@PathVariable String acctId, @RequestBody ChangesBody b) {
        return update.process(new UpdateRequest("PF5", b.state(), acctId, b.snapshot(), b.fields(),
                reenter(b.commarea())));
    }

    /** CBFEECAL via the CBFEEDRV request/line formats. */
    @PostMapping("/fees")
    public Map<String, Object> fees(@RequestBody FeeBody b) {
        FeeRequest req = new FeeRequest(b.acctId() == null ? "" : b.acctId(), b.groupId() == null ? "" : b.groupId(),
                b.cycleBal() == null ? new BigDecimal("0.00") : b.cycleBal().setScale(2, java.math.RoundingMode.DOWN),
                b.lateDays() == null ? 0 : b.lateDays(), b.cycleDays() == null ? 0 : b.cycleDays());
        FeeResult r = fees.calculate(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feeAmt", r.feeAmt().toPlainString());
        out.put("avgDaily", r.avgDaily().toPlainString());
        out.put("rc", r.rc());
        out.put("sysprint", List.copyOf(r.sysprint()));
        out.put("feeLine", feeDriver.feeLine(req, r));
        return out;
    }

    /** COBDATFT. */
    @PostMapping("/dates/convert")
    public Map<String, Object> convert(@RequestBody DateBody b) {
        char[] rec = DateFormatRoutine.record(b.inType(), b.inDate(), b.outType(), b.outDate(), "");
        int rc = dates.call(rec);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outDate", DateFormatRoutine.field(rec, DateFormatRoutine.COOUTDT, 20).stripTrailing());
        out.put("errorMsg", DateFormatRoutine.field(rec, DateFormatRoutine.COERMSG, 38).stripTrailing());
        out.put("returnCode", rc);
        return out;
    }
}
