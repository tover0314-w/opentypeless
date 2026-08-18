package com.opentypeless.android.rime.importer;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON reader with duplicate-key, depth, token and string bounds. */
final class StrictBoundedJson {
    private static final int MAXIMUM_BYTES = 1_048_576;
    private static final int MAXIMUM_DEPTH = 32;
    private static final int MAXIMUM_TOKENS = 20_000;
    private static final int MAXIMUM_STRING_CODE_POINTS = 8_192;

    private final String input;
    private int index;
    private int tokens;

    private StrictBoundedJson(String input) {
        this.input = input;
    }

    static Map<String, Object> parseObject(byte[] bytes) throws RimeImportException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw invalid();
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID, error);
        }
        StrictBoundedJson parser = new StrictBoundedJson(decoded);
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!(value instanceof Map<?, ?>) || parser.index != parser.input.length()) {
            throw invalid();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    private Object readValue(int depth) throws RimeImportException {
        if (depth > MAXIMUM_DEPTH || ++tokens > MAXIMUM_TOKENS) throw invalid();
        skipWhitespace();
        if (index >= input.length()) throw invalid();
        return switch (input.charAt(index)) {
            case '{' -> readObject(depth + 1);
            case '[' -> readArray(depth + 1);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readInteger();
        };
    }

    private Map<String, Object> readObject(int depth) throws RimeImportException {
        expect('{');
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (consume('}')) return result;
        while (true) {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != '"') throw invalid();
            String key = readString();
            if (result.containsKey(key)) throw invalid();
            skipWhitespace();
            expect(':');
            result.put(key, readValue(depth));
            skipWhitespace();
            if (consume('}')) return result;
            expect(',');
        }
    }

    private List<Object> readArray(int depth) throws RimeImportException {
        expect('[');
        ArrayList<Object> result = new ArrayList<>();
        skipWhitespace();
        if (consume(']')) return result;
        while (true) {
            result.add(readValue(depth));
            skipWhitespace();
            if (consume(']')) return result;
            expect(',');
        }
    }

    private String readString() throws RimeImportException {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (index < input.length()) {
            char character = input.charAt(index++);
            if (character == '"') {
                if (result.codePointCount(0, result.length()) > MAXIMUM_STRING_CODE_POINTS) {
                    throw invalid();
                }
                return result.toString();
            }
            if (character < 0x20) throw invalid();
            if (character != '\\') {
                if (Character.isSurrogate(character)) {
                    if (!Character.isHighSurrogate(character)
                            || index >= input.length()
                            || !Character.isLowSurrogate(input.charAt(index))) {
                        throw invalid();
                    }
                    result.append(character).append(input.charAt(index++));
                } else {
                    result.append(character);
                }
                continue;
            }
            if (index >= input.length()) throw invalid();
            char escaped = input.charAt(index++);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> appendUnicodeEscape(result);
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private void appendUnicodeEscape(StringBuilder output) throws RimeImportException {
        char first = readHexCharacter();
        if (Character.isLowSurrogate(first)) throw invalid();
        output.append(first);
        if (!Character.isHighSurrogate(first)) return;
        if (index + 2 > input.length() || input.charAt(index) != '\\'
                || input.charAt(index + 1) != 'u') {
            throw invalid();
        }
        index += 2;
        char second = readHexCharacter();
        if (!Character.isLowSurrogate(second)) throw invalid();
        output.append(second);
    }

    private char readHexCharacter() throws RimeImportException {
        if (index + 4 > input.length()) throw invalid();
        int value = 0;
        for (int count = 0; count < 4; count++) {
            int digit = Character.digit(input.charAt(index++), 16);
            if (digit < 0) throw invalid();
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Object readLiteral(String literal, Object value) throws RimeImportException {
        if (!input.startsWith(literal, index)) throw invalid();
        index += literal.length();
        return value;
    }

    private Long readInteger() throws RimeImportException {
        int start = index;
        if (consume('-')) {
            if (index >= input.length()) throw invalid();
        }
        if (consume('0')) {
            if (index < input.length() && Character.isDigit(input.charAt(index))) throw invalid();
        } else {
            if (index >= input.length() || input.charAt(index) < '1'
                    || input.charAt(index) > '9') {
                throw invalid();
            }
            while (index < input.length() && Character.isDigit(input.charAt(index))) index++;
        }
        if (index < input.length()) {
            char next = input.charAt(index);
            if (next == '.' || next == 'e' || next == 'E' || next == '+') throw invalid();
        }
        try {
            return Long.parseLong(input.substring(start, index));
        } catch (NumberFormatException error) {
            throw new RimeImportException(RimeImportException.Code.MANIFEST_INVALID, error);
        }
    }

    private void skipWhitespace() {
        while (index < input.length()) {
            char character = input.charAt(index);
            if (character != ' ' && character != '\n' && character != '\r' && character != '\t') {
                return;
            }
            index++;
        }
    }

    private boolean consume(char expected) {
        if (index < input.length() && input.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(char expected) throws RimeImportException {
        if (!consume(expected)) throw invalid();
    }

    private static RimeImportException invalid() {
        return new RimeImportException(RimeImportException.Code.MANIFEST_INVALID);
    }
}
