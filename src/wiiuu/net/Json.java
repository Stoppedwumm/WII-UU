package wiiuu.net;

/** Tiny JSON writer; enough for the handful of objects the gamepad page needs. */
final class Json {
    private final StringBuilder sb = new StringBuilder();
    private boolean needComma;

    Json obj() {
        comma();
        sb.append('{');
        needComma = false;
        return this;
    }

    Json endObj() {
        sb.append('}');
        needComma = true;
        return this;
    }

    Json arr() {
        comma();
        sb.append('[');
        needComma = false;
        return this;
    }

    Json endArr() {
        sb.append(']');
        needComma = true;
        return this;
    }

    Json key(String k) {
        comma();
        str(k);
        sb.append(':');
        needComma = false;
        return this;
    }

    Json val(String v) {
        comma();
        if (v == null) sb.append("null");
        else str(v);
        needComma = true;
        return this;
    }

    Json val(long v) {
        comma();
        sb.append(v);
        needComma = true;
        return this;
    }

    Json val(boolean v) {
        comma();
        sb.append(v);
        needComma = true;
        return this;
    }

    Json kv(String k, String v) {
        return key(k).val(v);
    }

    Json kv(String k, long v) {
        return key(k).val(v);
    }

    Json kv(String k, boolean v) {
        return key(k).val(v);
    }

    private void comma() {
        if (needComma) sb.append(',');
    }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
