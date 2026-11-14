package com.carddemo.common.codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Java port of {@code tools/parity/copybook.py#parse_copybook}.  It flattens a
 * copybook into elementary fields with byte offsets using exactly the same
 * rules as the comparator (levels, PIC, USAGE, OCCURS; REDEFINES statements
 * skipped), so a record the Java side writes is decoded by
 * {@code tools/parity/compare.py} into the same field names and values.
 * <p>
 * Copybooks are looked up, as in the Python codec, in {@code app/cpy},
 * {@code tools/parity/layouts} and {@code app/app-transaction-type-db2/cpy}.
 */
public final class CopybookParser {

    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(\\d{2})\\s+([A-Z0-9-]+)"
                    + "(?:\\s+REDEFINES\\s+[A-Z0-9-]+)?"
                    + "(?:\\s+PIC(?:TURE)?\\s+(?:IS\\s+)?([SXA9V()0-9]+))?"
                    + "(?:\\s+(?:USAGE\\s+(?:IS\\s+)?)?(COMP-3|COMP3|COMP(?:UTATIONAL)?(?:-[45])?|BINARY|PACKED-DECIMAL|DISPLAY))?"
                    + "(?:\\s+OCCURS\\s+(\\d+)(?:\\s+TIMES)?)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PIC_PIECE = Pattern.compile("([XA9SVZ,.+-])(?:\\((\\d+)\\))?");

    private final List<Path> copyDirs;
    private final Map<String, Copybook> cache = new ConcurrentHashMap<>();

    public CopybookParser(Path repoRoot) {
        this.copyDirs = List.of(repoRoot.resolve("app/cpy"), repoRoot.resolve("tools/parity/layouts"),
                repoRoot.resolve("app/app-transaction-type-db2/cpy"));
    }

    /** Parses (and caches) the copybook with the given member name, e.g. {@code CVACT01Y}. */
    public Copybook get(String name) {
        return cache.computeIfAbsent(name.toUpperCase(Locale.ROOT), n -> new Copybook(n, parse(find(n))));
    }

    Path find(String name) {
        for (Path dir : copyDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                    String fn = p.getFileName().toString();
                    int dot = fn.lastIndexOf('.');
                    if (dot < 0) {
                        continue;
                    }
                    String stem = fn.substring(0, dot).toUpperCase(Locale.ROOT);
                    String ext = fn.substring(dot).toLowerCase(Locale.ROOT);
                    if (stem.equals(name) && (ext.equals(".cpy") || ext.equals(".cbl") || ext.equals(".dcl"))) {
                        return p;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        throw new IllegalArgumentException("copybook not found: " + name);
    }

    static String expandTabs(String line, int tab) {
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '\t') {
                int n = tab - (sb.length() % tab);
                sb.append(" ".repeat(n));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static List<String> statements(Path path) {
        List<String> text = new ArrayList<>();
        String content;
        try {
            content = Files.readString(path, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String raw : content.split("\\r\\n|\\r|\\n", -1)) {
            String line = expandTabs(raw, 8);
            if (line.length() > 6 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
                continue;
            }
            line = line.length() > 72 ? line.substring(6, 72) : line.length() > 6 ? line.substring(6) : "";
            if (line.strip().startsWith("*")) {
                continue;
            }
            text.add(line.strip());
        }
        List<String> out = new ArrayList<>();
        for (String s : String.join(" ", text).split("\\.", -1)) {
            if (!s.strip().isEmpty()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    static int picCount(String piece) {
        int n = 0;
        Matcher m = PIC_PIECE.matcher(piece.toUpperCase(Locale.ROOT));
        while (m.find()) {
            n += m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        }
        return n;
    }

    private record PicInfo(FieldKind kind, int scale, int digits, boolean signed) {}

    static PicInfo picInfo(String pic) {
        pic = pic.toUpperCase(Locale.ROOT);
        if (pic.contains("X") || pic.contains("A")) {
            return new PicInfo(FieldKind.TEXT, 0, picCount(pic), false);
        }
        boolean signed = pic.startsWith("S");
        String body = signed ? pic.substring(1) : pic;
        int v = body.indexOf('V');
        String integer = v < 0 ? body : body.substring(0, v);
        String fraction = v < 0 ? "" : body.substring(v + 1);
        int scale = fraction.isEmpty() ? 0 : picCount(fraction);
        return new PicInfo(FieldKind.DISPLAY, scale, picCount(integer) + scale, signed);
    }

    private record Pending(int level, int count, int start, int index) {}

    private static void expandOccurs(List<CopybookField> fields, Pending p, int offset, boolean renameFirst) {
        int span = offset - p.start();
        List<CopybookField> block = new ArrayList<>(fields.subList(p.index(), fields.size()));
        for (int rep = 1; rep < p.count(); rep++) {
            for (CopybookField f : block) {
                fields.add(f.shifted(f.name() + "[" + (rep + 1) + "]", span * rep));
            }
        }
        if (renameFirst) {
            for (int i = 0; i < block.size(); i++) {
                CopybookField f = block.get(i);
                fields.set(p.index() + i, f.withName(f.name() + "[1]"));
            }
        }
    }

    static List<CopybookField> parse(Path path) {
        List<CopybookField> fields = new ArrayList<>();
        int offset = 0;
        Deque<Pending> pending = new ArrayDeque<>();
        for (String statement : statements(path)) {
            Matcher m = ITEM.matcher(statement);
            if (!m.lookingAt() || statement.toUpperCase(Locale.ROOT).contains("REDEFINES")) {
                continue;
            }
            int level = Integer.parseInt(m.group(1));
            String item = m.group(2);
            String pic = m.group(3);
            String usage = m.group(4) == null ? "" : m.group(4).toUpperCase(Locale.ROOT);
            String occurs = m.group(5);
            if (level == 66 || level == 77 || level == 88) {
                continue;
            }
            while (!pending.isEmpty() && level <= pending.peek().level()) {
                Pending p = pending.pop();
                int span = offset - p.start();
                expandOccurs(fields, p, offset, true);
                offset = p.start() + span * p.count();
            }
            if (pic == null) {
                if (occurs != null) {
                    pending.push(new Pending(level, Integer.parseInt(occurs), offset, fields.size()));
                }
                continue;
            }
            PicInfo info = picInfo(pic);
            FieldKind kind = info.kind();
            int length;
            if (kind == FieldKind.TEXT) {
                length = info.digits();
            } else if (usage.equals("COMP-3") || usage.equals("COMP3") || usage.equals("PACKED-DECIMAL")) {
                kind = FieldKind.COMP3;
                length = info.digits() / 2 + 1;
            } else if (usage.startsWith("COMP") || usage.equals("BINARY")) {
                kind = FieldKind.COMP;
                length = info.digits() <= 4 ? 2 : info.digits() <= 9 ? 4 : 8;
            } else {
                length = info.digits();
            }
            int count = occurs == null ? 1 : Integer.parseInt(occurs);
            for (int i = 0; i < count; i++) {
                String fname = item.equalsIgnoreCase("FILLER") ? "FILLER@" + offset : count == 1 ? item : item + "[" + (i + 1) + "]";
                fields.add(new CopybookField(fname, offset, length, kind, info.scale(), info.signed(), info.digits()));
                offset += length;
            }
        }
        while (!pending.isEmpty()) {
            Pending p = pending.pop();
            // same as the Python codec: trailing OCCURS groups are repeated but the first copy keeps its name
            int span = offset - p.start();
            expandOccurs(fields, p, offset, false);
            offset = p.start() + span * p.count();
        }
        return List.copyOf(fields);
    }
}
