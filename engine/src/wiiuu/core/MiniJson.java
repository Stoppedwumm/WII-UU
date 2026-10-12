package wiiuu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for API answers (OpenBased): objects become {@code Map<String, Object>},
 * arrays {@code List<Object>}, numbers {@code Double}, plus String, Boolean and null.
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        Object v = p.value();
        p.space();
        if (p.i != p.s.length()) throw p.error("trailing characters");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static String string(Object v) {
        return v instanceof String str ? str : null;
    }

    public static Double number(Object v) {
        return v instanceof Double d ? d : null;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                space();
                if (peek('}')) return m;
                do {
                    space();
                    String k = str();
                    space();
                    expect(':');
                    m.put(k, value());
                    space();
                } while (take(','));
                expect('}');
                return m;
            }
            case '[': {
                i++;
                List<Object> l = new ArrayList<>();
                space();
                if (peek(']')) return l;
                do l.add(value()); while (take(','));
                space();
                expect(']');
                return l;
            }
            case '"':
                return str();
            case 't':
                word("true");
                return Boolean.TRUE;
            case 'f':
                word("false");
                return Boolean.FALSE;
            case 'n':
                word("null");
                return null;
            default: {
                int from = i;
                while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                if (from == i) throw error("unexpected '" + c + "'");
                return Double.parseDouble(s.substring(from, i));
            }
        }
    }

    private String str() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);                          // \" \\ \/
            }
        }
        throw error("unterminated string");
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private boolean take(char c) {
        space();
        return peek(c);
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private void word(String w) {
        if (!s.startsWith(w, i)) throw error("expected " + w);
        i += w.length();
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("bad JSON at " + i + ": " + what);
    }
}
