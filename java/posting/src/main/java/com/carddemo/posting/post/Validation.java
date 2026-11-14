package com.carddemo.posting.post;

/**
 * Outcome of {@code 1500-VALIDATE-TRAN}: {@code WS-VALIDATION-TRAILER} (reason 9(04) + text X(76))
 * plus what a valid posting needs (cross-referenced account id and the account record read).
 */
public record Validation(DailyTranRecord tran, int reason, String reasonDesc, Long xrefAcctId, Account account) {

    public boolean valid() {
        return reason == 0;
    }

    /** {@code WS-VALIDATION-TRAILER}: 4-digit reason and 76-character description. */
    public String trailer() {
        return String.format("%04d", reason) + com.carddemo.posting.Cobol.pic(reasonDesc, 76);
    }
}
