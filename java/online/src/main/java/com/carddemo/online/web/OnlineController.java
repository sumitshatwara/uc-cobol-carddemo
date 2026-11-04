package com.carddemo.online.web;

import com.carddemo.online.account.AccountUpdateService;
import com.carddemo.online.account.AccountUpdateSession;
import com.carddemo.online.account.AccountViewService;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.fees.FeeDriver;
import com.carddemo.online.fees.FeeRequest;
import com.carddemo.online.fees.LateFeeCalculator;
import com.carddemo.online.files.Account;
import com.carddemo.online.files.OnlineDemoData;
import com.carddemo.online.menu.MenuService;
import com.carddemo.online.signon.SignOnService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/online")
public class OnlineController {
    private final SignOnService signOn;
    private final MenuService menu;
    private final AccountViewService accountView;
    private final AccountUpdateService accountUpdate;
    private final LateFeeCalculator fees;
    private final OnlineDemoData demoData;

    public OnlineController(SignOnService signOn, MenuService menu, AccountViewService accountView,
                            AccountUpdateService accountUpdate, LateFeeCalculator fees,
                            OnlineDemoData demoData) {
        this.signOn = signOn;
        this.menu = menu;
        this.accountView = accountView;
        this.accountUpdate = accountUpdate;
        this.fees = fees;
        this.demoData = demoData;
    }

    @GetMapping("/signon")
    public SignOnService.SignOnResponse signOnScreen() {
        return signOn.initialScreen();
    }

    @PostMapping("/signon")
    public SignOnService.SignOnResponse signOn(@RequestBody SignOnRequest request) {
        return signOn.signOn(request.aid(), request.userId(), request.password());
    }

    @GetMapping("/menu")
    public MenuService.MenuResponse menu(@RequestParam(defaultValue = "U") String userType) {
        return menu.menu(userType);
    }

    @PostMapping("/menu/selection")
    public MenuService.MenuSelectionResponse menuSelection(@RequestBody MenuSelectionRequest request) {
        return menu.select(request.aid(), request.option(), request.commarea());
    }

    @GetMapping("/accounts")
    public AccountViewService.ViewResponse accountQuery(@RequestParam(required = false) String acctId,
                                                        @RequestParam(defaultValue = "ENTER") String aid) {
        return accountView.view(acctId, aid, null);
    }

    @GetMapping("/accounts/{acctId}")
    public AccountViewService.ViewResponse account(@PathVariable String acctId,
                                                   @RequestParam(defaultValue = "ENTER") String aid) {
        return accountView.view(acctId, aid, null);
    }

    @GetMapping("/accounts/{acctId}/edit")
    public AccountUpdateService.UpdateResponse accountEdit(@PathVariable String acctId) {
        return accountUpdate.search(acctId, null);
    }

    @PutMapping("/accounts/{acctId}")
    public AccountUpdateService.UpdateResponse updateAccount(@PathVariable String acctId,
                                                              @RequestBody AccountUpdateRequest request) {
        return accountUpdate.update(acctId, request.aid(), request.session(), request.input());
    }

    @PostMapping("/fees")
    public FeeResponse fee(@RequestBody FeeInput input) {
        FeeRequest request = new FeeRequest();
        request.acctId = fixed(input.acctId(), 11);
        request.groupId = fixed(input.groupId(), 10);
        request.cycleBal = new BigDecimal(input.cycleBal().asText()).setScale(2, RoundingMode.DOWN);
        request.lateDays = (short) input.lateDays();
        request.cycleDays = (short) input.cycleDays();
        List<String> sysprint = new ArrayList<>();
        fees.calculate(request, sysprint);
        return new FeeResponse(trim(request.acctId), trim(request.groupId),
                request.cycleBal.toPlainString(), request.lateDays, request.cycleDays,
                request.feeAmt.toPlainString(), request.avgDaily.toPlainString(), request.rc, sysprint);
    }

    @PostMapping("/demo-data/reset")
    public Map<String, String> resetDemoData() {
        demoData.reset();
        return Map.of("status", "ok");
    }

    private static String fixed(String value, int width) {
        String source = value == null ? "" : value;
        return source.length() >= width ? source.substring(0, width)
                : source + " ".repeat(width - source.length());
    }

    private static String trim(String value) {
        return value == null ? "" : value.stripTrailing();
    }

    public record SignOnRequest(String aid, String userId, String password) {
    }

    public record MenuSelectionRequest(String aid, String option, CardDemoCommarea commarea) {
    }

    public record AccountUpdateRequest(String aid, AccountUpdateSession session, Map<String, Object> input) {
    }

    public record FeeInput(String acctId, String groupId, JsonNode cycleBal, int lateDays, int cycleDays) {
    }

    public record FeeResponse(String acctId, String groupId, String cycleBal, short lateDays,
                              short cycleDays, String feeAmt, String avgDaily, short rc,
                              List<String> sysprint) {
    }
}
