package com.carddemo.common.codec;

import com.carddemo.common.estate.EstateRoot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CopybookParser {
    private static final Pattern ITEM = Pattern.compile(
            "^\\s*(\\d{2})\\s+([A-Z0-9-]+)" +
                    "(?:\\s+REDEFINES\\s+[A-Z0-9-]+)?" +
                    "(?:\\s+PIC(?:TURE)?\\s+(?:IS\\s+)?([SXA9V()0-9]+))?" +
                    "(?:\\s+(?:USAGE\\s+(?:IS\\s+)?)?(COMP-3|COMP3|COMP(?:UTATIONAL)?(?:-[45])?|BINARY|PACKED-DECIMAL|DISPLAY))?" +
                    "(?:\\s+OCCURS\\s+(\\d+)(?:\\s+TIMES)?)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PIC_SYMBOL = Pattern.compile("([XA9SVZ,.+-])(?:\\((\\d+)\\))?");

    private CopybookParser() {
    }

    public static List<CopybookField> parse(String name) {
        List<CopybookField> fields = new ArrayList<>();
        int offset = 0;
        List<PendingOccurs> pending = new ArrayList<>();
        for (String statement : statements(findCopybook(name))) {
            Matcher matcher = ITEM.matcher(statement);
            if (!matcher.find() || statement.toUpperCase(Locale.ROOT).contains("REDEFINES")) {
                continue;
            }
            int level = Integer.parseInt(matcher.group(1));
            String item = matcher.group(2);
            String pic = matcher.group(3);
            String usage = matcher.group(4);
            String occurs = matcher.group(5);
            if (level == 66 || level == 77 || level == 88) {
                continue;
            }
            while (!pending.isEmpty() && level <= pending.getLast().level()) {
                PendingOccurs itemOccurs = pending.removeLast();
                int span = offset - itemOccurs.start();
                List<CopybookField> block = new ArrayList<>(fields.subList(itemOccurs.index(), fields.size()));
                for (int rep = 1; rep < itemOccurs.count(); rep++) {
                    for (CopybookField field : block) {
                        fields.add(new CopybookField(field.name() + "[" + (rep + 1) + "]",
                                field.offset() + span * rep, field.length(), field.kind(), field.scale(),
                                field.signed(), field.digits()));
                    }
                }
                for (int i = 0; i < block.size(); i++) {
                    CopybookField field = block.get(i);
                    fields.set(itemOccurs.index() + i, new CopybookField(field.name() + "[1]",
                            field.offset(), field.length(), field.kind(), field.scale(), field.signed(), field.digits()));
                }
                offset = itemOccurs.start() + span * itemOccurs.count();
            }
            if (pic == null) {
                if (occurs != null) {
                    pending.add(new PendingOccurs(level, Integer.parseInt(occurs), offset, fields.size()));
                }
                continue;
            }
            PicInfo info = picInfo(pic);
            String normalizedUsage = usage == null ? "" : usage.toUpperCase(Locale.ROOT);
            int length;
            CopybookField.Kind kind = info.kind();
            if (kind == CopybookField.Kind.TEXT) {
                length = info.digits();
            } else if (List.of("COMP-3", "COMP3", "PACKED-DECIMAL").contains(normalizedUsage)) {
                kind = CopybookField.Kind.COMP3;
                length = info.digits() / 2 + 1;
            } else if (List.of("COMP", "COMPUTATIONAL", "BINARY", "COMP-4", "COMPUTATIONAL-4",
                    "COMP-5", "COMPUTATIONAL-5").contains(normalizedUsage)) {
                kind = CopybookField.Kind.COMP;
                length = info.digits() <= 4 ? 2 : info.digits() <= 9 ? 4 : 8;
            } else {
                length = info.digits();
            }
            int count = occurs == null ? 1 : Integer.parseInt(occurs);
            for (int index = 0; index < count; index++) {
                String fieldName = item.equalsIgnoreCase("FILLER")
                        ? "FILLER@" + offset
                        : count == 1 ? item : item + "[" + (index + 1) + "]";
                fields.add(new CopybookField(fieldName, offset, length, kind, info.scale(), info.signed(), info.digits()));
                offset += length;
            }
        }
        while (!pending.isEmpty()) {
            PendingOccurs itemOccurs = pending.removeLast();
            int span = offset - itemOccurs.start();
            List<CopybookField> block = new ArrayList<>(fields.subList(itemOccurs.index(), fields.size()));
            for (int rep = 1; rep < itemOccurs.count(); rep++) {
                for (CopybookField field : block) {
                    fields.add(new CopybookField(field.name() + "[" + (rep + 1) + "]",
                            field.offset() + span * rep, field.length(), field.kind(), field.scale(),
                            field.signed(), field.digits()));
                }
            }
            offset = itemOccurs.start() + span * itemOccurs.count();
        }
        return List.copyOf(fields);
    }

    public static int recordLength(List<CopybookField> fields) {
        return fields.stream().mapToInt(field -> field.offset() + field.length()).max().orElse(0);
    }

    private static Path findCopybook(String name) {
        List<Path> directories = List.of(
                EstateRoot.root().resolve("app/cpy"),
                EstateRoot.root().resolve("tools/parity/layouts"),
                EstateRoot.root().resolve("app/app-transaction-type-db2/cpy"));
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var children = Files.list(directory)) {
                Path match = children.filter(path -> {
                    String extension = extension(path);
                    String stem = stem(path);
                    return stem.equalsIgnoreCase(name)
                            && List.of(".cpy", ".cbl", ".dcl").contains(extension.toLowerCase(Locale.ROOT));
                }).findFirst().orElse(null);
                if (match != null) {
                    return match;
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to search copybook directory " + directory, exception);
            }
        }
        throw new IllegalArgumentException("copybook not found: " + name);
    }

    private static String extension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot);
    }

    private static String stem(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static List<String> statements(Path path) {
        StringBuilder source = new StringBuilder();
        try {
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = expandTabs(raw);
                if (line.length() > 6 && (line.charAt(6) == '*' || line.charAt(6) == '/')) {
                    continue;
                }
                line = line.length() > 72 ? line.substring(6, 72) : line.length() > 6 ? line.substring(6) : "";
                if (line.stripLeading().startsWith("*")) {
                    continue;
                }
                source.append(line.strip()).append(' ');
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read copybook " + path, exception);
        }
        List<String> statements = new ArrayList<>();
        for (String statement : source.toString().split("\\.")) {
            if (!statement.isBlank()) {
                statements.add(statement.strip());
            }
        }
        return statements;
    }

    private static PicInfo picInfo(String picture) {
        String pic = picture.toUpperCase(Locale.ROOT);
        int digits = picCount(pic);
        if (pic.contains("X") || pic.contains("A")) {
            return new PicInfo(CopybookField.Kind.TEXT, 0, digits, false);
        }
        boolean signed = pic.startsWith("S");
        String body = signed ? pic.substring(1) : pic;
        int decimal = body.indexOf('V');
        int scale = decimal < 0 ? 0 : picCount(body.substring(decimal + 1));
        int totalDigits = (decimal < 0 ? picCount(body) : picCount(body.substring(0, decimal)) + scale);
        return new PicInfo(CopybookField.Kind.DISPLAY, scale, totalDigits, signed);
    }

    private static int picCount(String picture) {
        Matcher matcher = PIC_SYMBOL.matcher(picture.toUpperCase(Locale.ROOT));
        int count = 0;
        while (matcher.find()) {
            count += matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
        }
        return count;
    }

    private static String expandTabs(String line) {
        StringBuilder expanded = new StringBuilder();
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\t') {
                int spaces = 8 - expanded.length() % 8;
                expanded.append(" ".repeat(spaces));
            } else {
                expanded.append(character);
            }
        }
        return expanded.toString();
    }

    private record PicInfo(CopybookField.Kind kind, int scale, int digits, boolean signed) {
    }

    private record PendingOccurs(int level, int count, int start, int index) {
    }
}
