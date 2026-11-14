package com.carddemo.trantype;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.TestRepo;
import com.carddemo.common.fixture.FixtureLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Replays every golden fixture case of both chains on plain objects (in-memory Db2 fake) and
 * checks SYSOUT, step RC and the transaction_type snapshot against the recorded legacy output.
 * The authoritative regression is {@code make parity-java} against PostgreSQL; this keeps the
 * unit layer honest without a database.
 */
class GoldenReplayTest {

    private static final Pattern SEED_ROW = Pattern.compile("\\('([^']*)','([^']*)'\\)");

    @ParameterizedTest(name = "{0}/{1}")
    @CsvSource({
            "trantype-update,t01-add,MNTTRDB2", "trantype-update,t02-update,MNTTRDB2",
            "trantype-update,t03-delete,MNTTRDB2", "trantype-update,t04-comments,MNTTRDB2",
            "trantype-update,t05-delete-missing,MNTTRDB2", "trantype-update,t06-duplicate-add,MNTTRDB2",
            "trantype-update,t07-mixed-batch,MNTTRDB2", "trantype-dynamic,d01-update-then-apply,MNTTRDYN",
            "trantype-dynamic,d02-delete-statement,MNTTRDYN", "trantype-dynamic,d03-bad-statement,MNTTRDYN"})
    void goldenCaseReplays(String chain, String caseName, String job) throws Exception {
        Path dir = TestRepo.fixture(chain, caseName);
        Map<String, String> seed = new TreeMap<>();
        Matcher m = SEED_ROW.matcher(java.nio.file.Files.readString(dir.resolve("input/db/seed.sql")));
        while (m.find()) {
            seed.put(m.group(1), m.group(2));
        }
        InMemoryDb2Session db = new InMemoryDb2Session(seed);
        DsnCommand cmd = DsnCommand.fromJcl(TestRepo.root().resolve("app/app-transaction-type-db2/jcl/" + job + ".jcl"));
        List<String> dynsql = job.equals("MNTTRDYN") ? FixtureLoader.readRecords(dir, "DYNSQL", 80) : null;
        Harness.Result r = Harness.run(cmd, db, FixtureLoader.readRecords(dir, "INPFILE", 53), dynsql);

        assertThat(r.sysout()).containsExactlyElementsOf(TestRepo.expectedLines(chain, caseName, "sysout/" + job + ".STEP1.txt"));
        assertThat(TestRepo.expectedText(chain, caseName, "rc.json")).contains("\"STEP1\": " + r.rc());
        assertThat(db.csv()).isEqualTo(TestRepo.expectedText(chain, caseName, "db/carddemo.transaction_type.csv"));
    }
}
