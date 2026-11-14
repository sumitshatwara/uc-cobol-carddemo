package com.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.Copybook;
import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.FieldKind;
import com.carddemo.common.codec.RecordCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Java codec must agree with tools/parity/copybook.py on layouts and values. */
class CodecTest {

    @Test
    void accountLayoutMatchesThePythonCodec() {
        Copybook cb = TestRepo.copybooks().get("CVACT01Y");
        assertThat(cb.recordLength()).isEqualTo(300);
        CopybookField bal = cb.field("ACCT-CURR-BAL");
        assertThat(bal).isEqualTo(new CopybookField("ACCT-CURR-BAL", 12, 12, FieldKind.DISPLAY, 2, true, 12));
        assertThat(cb.fields().get(cb.fields().size() - 1).name()).isEqualTo("FILLER@122");
    }

    @Test
    void recordLengthsOfEveryChainLayout() {
        Map<String, Integer> expected = Map.of("CVACT03Y", 50, "CVTRA01Y", 50, "CVTRA02Y", 50, "CVTRA03Y", 60,
                "CVTRA04Y", 60, "CVTRA05Y", 350, "CVTRA06Y", 350, "REJTRAN", 430);
        expected.forEach((name, len) -> assertThat(TestRepo.copybooks().get(name).recordLength()).as(name).isEqualTo(len));
    }

    @Test
    void decodesOverpunchFromAGoldenSysoutRecord() {
        // fixtures/interest/i02-tie-half-cent: TCATBAL record DISPLAYed by CBACT04C, balance 1235.5{ -> +1235.50
        String line = TestRepo.expectedLines("interest", "i02-tie-half-cent", "sysout/INTCALC.STEP15.txt").get(1);
        Map<String, Object> rec = RecordCodec.decodeLine(TestRepo.copybooks().get("CVTRA01Y"), line);
        assertThat(rec.get("TRANCAT-ACCT-ID")).isEqualTo(new BigDecimal("2003"));
        assertThat(rec.get("TRAN-CAT-BAL")).isEqualTo(new BigDecimal("1235.50"));
    }

    @Test
    void roundTripsEveryGoldenTransactionRecord() {
        Copybook cb = TestRepo.copybooks().get("CVTRA05Y");
        List<String> lines = TestRepo.expectedLines("interest", "i02-tie-half-cent", "datasets/AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt");
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            String encoded = RecordCodec.encodeLine(cb, RecordCodec.decodeLine(cb, line)).stripTrailing();
            assertThat(encoded).isEqualTo(line.stripTrailing());
        }
    }

    @Test
    void negativeZonedUsesEbcdicOverpunch() {
        assertThat(new String(RecordCodec.encodeDisplay(new BigDecimal("-12.34"), 6, 2, true))).isEqualTo("00123M");
        assertThat(new String(RecordCodec.encodeDisplay(new BigDecimal("12.30"), 6, 2, true))).isEqualTo("00123{");
        assertThat(new String(RecordCodec.encodeDisplay(new BigDecimal("1234567"), 4, 0, false))).isEqualTo("4567");
    }

    @Test
    void packedDecimalRoundTrip() {
        byte[] p = RecordCodec.encodePacked(new BigDecimal("-1234.56"), 4, 2, true);
        assertThat(p).containsExactly(0x01, 0x23, 0x45, 0x6D);
        Copybook cb = new Copybook("T", List.of(new CopybookField("AMT", 0, 4, FieldKind.COMP3, 2, true, 7)));
        assertThat(RecordCodec.decode(cb, p).get("AMT")).isEqualTo(new BigDecimal("-1234.56"));
    }

    @Test
    void encodingRefusesSilentRounding() {
        assertThatThrownBy(() -> RecordCodec.encodeDisplay(new BigDecimal("12.355"), 6, 2, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("RoundingMode.DOWN");
    }
}
