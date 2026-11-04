package com.carddemo.online;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.account.AccountUpdateService;
import com.carddemo.online.account.AccountViewService;
import com.carddemo.online.fees.FeeRequest;
import com.carddemo.online.fees.LateFeeCalculator;
import com.carddemo.online.signon.SignOnService;

import java.math.BigDecimal;
import java.util.ArrayList;

abstract class OnlineTestSupport {
    protected final FrozenClock clock = FrozenClock.defaultClock();
    protected final InMemoryOnlineFiles files = new InMemoryOnlineFiles();
    protected final SignOnService signon = new SignOnService(files, clock);
    protected final AccountViewService view = new AccountViewService(files, clock);
    protected final AccountUpdateService update = new AccountUpdateService(files, clock);

    protected static FeeRequest fee(String group, String balance, int lateDays, int cycleDays) {
        return fee("00000000001", group, balance, lateDays, cycleDays);
    }

    protected static FeeRequest fee(String accountId, String group, String balance,
                                    int lateDays, int cycleDays) {
        FeeRequest request = new FeeRequest();
        request.acctId = accountId;
        request.groupId = group;
        request.cycleBal = new BigDecimal(balance);
        request.lateDays = (short) lateDays;
        request.cycleDays = (short) cycleDays;
        return request;
    }

    protected static FeeRequest calculate(String group, String balance, int lateDays, int cycleDays) {
        FeeRequest request = fee(group, balance, lateDays, cycleDays);
        new LateFeeCalculator().calculate(request, new ArrayList<>());
        return request;
    }
}
