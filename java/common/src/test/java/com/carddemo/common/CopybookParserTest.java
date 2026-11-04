package com.carddemo.common;

import com.carddemo.common.codec.CopybookField;
import com.carddemo.common.codec.CopybookParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CopybookParserTest {
    @Test
    void cvact01yFieldsMatchOffsetsAndNumericTypes() {
        var fields = CopybookParser.parse("CVACT01Y");
        assertThat(fields.getFirst()).isEqualTo(
                new CopybookField("ACCT-ID", 0, 11, CopybookField.Kind.DISPLAY, 0, false, 11));
        assertThat(fields.get(2)).isEqualTo(
                new CopybookField("ACCT-CURR-BAL", 12, 12, CopybookField.Kind.DISPLAY, 2, true, 12));
        assertThat(CopybookParser.recordLength(fields)).isEqualTo(300);
        assertThat(fields.getLast().isFiller()).isTrue();
    }
}
