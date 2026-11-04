package com.carddemo.common;

import com.carddemo.common.export.JobRc;
import com.carddemo.common.export.OutputWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class OutputWriterFormatTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void manifestAndRcMatchPythonIndentAndOrdering() throws Exception {
        OutputWriter writer = new OutputWriter(temporaryDirectory, null, null);
        writer.writeDataset("Z.DATA", "text", 4, List.of("a   ".getBytes(), "bc  ".getBytes()));
        writer.writeDataset("A.DATA", "text", 4, List.of());
        Map<String, JobRc> rc = Map.of("JOB", new JobRc(0, new TreeMap<>(Map.of("STEP02", 0, "STEP01", 0))));
        writer.writeRcJson(rc);

        assertThat(Files.readString(temporaryDirectory.resolve("datasets/Z.DATA.txt"))).isEqualTo("a\nbc\n");
        assertThat(Files.readString(temporaryDirectory.resolve("manifest.json"))).isEqualTo(
                "{\n \"A.DATA\": {\n  \"layout\": \"text\",\n  \"lrecl\": 4,\n  \"records\": 0\n },\n" +
                        " \"Z.DATA\": {\n  \"layout\": \"text\",\n  \"lrecl\": 4,\n  \"records\": 2\n }\n}");
        assertThat(Files.readString(temporaryDirectory.resolve("rc.json"))).isEqualTo(
                "{\n \"JOB\": {\n  \"maxrc\": 0,\n  \"steps\": {\n   \"STEP01\": 0,\n   \"STEP02\": 0\n  }\n }\n}");
        assertThat(writer.gdgName("AWS.M2.CARDDEMO.SYSTRAN", 1))
                .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN.G0001V00");
    }

    @Test
    void sysoutUsesRecordPyTrailingNewlineConvention() throws Exception {
        OutputWriter writer = new OutputWriter(temporaryDirectory, null, null);
        writer.writeSysout("INTCALC", "STEP15", List.of("DISPLAY ONE", "DISPLAY TWO"));
        writer.writeSysout("JOB", "EMPTY", List.of());
        assertThat(Files.readString(temporaryDirectory.resolve("sysout/INTCALC.STEP15.txt")))
                .isEqualTo("DISPLAY ONE\nDISPLAY TWO\n");
        assertThat(Files.readString(temporaryDirectory.resolve("sysout/JOB.EMPTY.txt"))).isEmpty();
    }
}
