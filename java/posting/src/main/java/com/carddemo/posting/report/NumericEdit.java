package com.carddemo.posting.report;

import com.carddemo.common.cobol.CobolNumeric;
import java.math.BigDecimal;

/** The CVTRA07Y edit pictures {@code -ZZZ,ZZZ,ZZZ.ZZ} and {@code +ZZZ,ZZZ,ZZZ.ZZ}. */
public final class NumericEdit {

    private NumericEdit() {}

    /**
     * MOVE of an S9(09)V99 value to a sign + {@code ZZZ,ZZZ,ZZZ.ZZ} picture (15 characters).  Every
     * digit position is Z, so a zero value edits to all spaces; a {@code -} sign position prints a
     * space for positive values, {@code +} prints {@code +}.
     *
     * @see "BR-POST-036 BR-POST-037"
     */
    public static String edit(BigDecimal value, char signSymbol) {
        BigDecimal f = CobolNumeric.fit(value, 9, 2);
        if (f.signum() == 0) {
            return " ".repeat(15);
        }
        String digits = f.abs().unscaledValue().toString();
        digits = "0".repeat(11 - digits.length()) + digits;
        StringBuilder sb = new StringBuilder();
        sb.append(f.signum() < 0 ? '-' : (signSymbol == '+' ? '+' : ' '));
        boolean significant = false;
        for (int i = 0; i < 9; i++) {
            if (i == 3 || i == 6) {
                sb.append(significant ? ',' : ' ');
            }
            char c = digits.charAt(i);
            significant |= c != '0';
            sb.append(significant ? c : ' ');
        }
        sb.append('.').append(digits, 9, 11);
        return sb.toString();
    }
}
