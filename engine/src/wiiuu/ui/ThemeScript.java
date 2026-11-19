package wiiuu.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * WII-UU's theme language: small text files (.wtheme) that recolour the menu. A theme starts from
 * the built-in light or dark colours and changes what it wants, with variables, colour functions,
 * per-console tile colours and parts for light and dark mode:
 *
 * <pre>
 * theme "Sunset"
 * by "Someone"
 * base dark                          # light, dark, or auto (follow the computer)
 *
 * let sun = #FF7A3D
 * accent = sun
 * card = lighten(#1A1033, 8%)
 * text-dim = mix(text, card, 35%)
 * background = gradient(#2B1748, #120A24)
 * consoles = mix(it, sun, 20%)       # every console tile; "it" is its own colour
 * console n64 = #00A651
 * font "Nunito", "DejaVu Sans"
 * when dark { stripes = none }
 * </pre>
 *
 * Statements end at the end of the line; # (followed by a space) and // start comments. Errors
 * name the line and column, and suggest the closest name when one is misspelt. Evaluating needs
 * no screen: {@code wiiuu --theme-check} uses it too.
 */
public final class ThemeScript {

    /** Every colour a theme can set, with what it is for (the order of the reference). */
    public static final Map<String, String> PROPERTIES = new LinkedHashMap<>();

    static {
        PROPERTIES.put("accent", "highlights: the selection frame, the UU of the logo, switches, buttons");
        PROPERTIES.put("text", "main text");
        PROPERTIES.put("text-dim", "secondary text: hints, captions, dates");
        PROPERTIES.put("card", "cards, panels and the dock");
        PROPERTIES.put("divider", "lines between things");
        PROPERTIES.put("dot", "page dots that aren't the current page");
        PROPERTIES.put("icon", "dock icons");
        PROPERTIES.put("button", "buttons and the top bar's pills");
        PROPERTIES.put("background-top", "the menu background at the top (background sets both)");
        PROPERTIES.put("background-bottom", "the menu background at the bottom");
        PROPERTIES.put("stripes", "the background's thin pinstripes (none: no stripes)");
        PROPERTIES.put("glow", "the soft light at the top left");
        PROPERTIES.put("accent-glow", "the coloured light at the bottom right");
        PROPERTIES.put("shelf", "the shine on tiles");
        PROPERTIES.put("outline", "the thin outline around tiles and cards");
        PROPERTIES.put("shadow", "the colour of shadows");
        PROPERTIES.put("dim", "the veil behind dialogs (the power menu, the GamePad screen)");
        PROPERTIES.put("toast", "the little messages at the bottom");
        PROPERTIES.put("boot-top", "the start-up animation's background at the top (boot sets both)");
        PROPERTIES.put("boot-bottom", "the start-up animation's background at the bottom");
        PROPERTIES.put("boot-fade", "the colour the start-up animation fades through");
    }

    /** Shorthands that set two colours at once, from a gradient or one colour. */
    private static final Map<String, String[]> PAIRS = Map.of(
            "background", new String[]{"background-top", "background-bottom"},
            "boot", new String[]{"boot-top", "boot-bottom"});

    /** The colour functions, with their arguments (for the reference and error messages). */
    public static final Map<String, String> FUNCTIONS = new LinkedHashMap<>();

    static {
        FUNCTIONS.put("rgb", "rgb(red, green, blue[, opacity]) - 0-255 each (or %), opacity 0-1 or %");
        FUNCTIONS.put("hsl", "hsl(hue, saturation, lightness[, opacity]) - hue in degrees, the others in %");
        FUNCTIONS.put("mix", "mix(a, b[, amount]) - amount of b in a, 50% if left out");
        FUNCTIONS.put("lighten", "lighten(colour, amount) - mixed with white");
        FUNCTIONS.put("darken", "darken(colour, amount) - mixed with black");
        FUNCTIONS.put("alpha", "alpha(colour, opacity) - the same colour, see-through (0% = invisible)");
        FUNCTIONS.put("saturate", "saturate(colour, amount) - more colourful");
        FUNCTIONS.put("desaturate", "desaturate(colour, amount) - greyer");
        FUNCTIONS.put("grey", "grey(colour) - the colour without its colour");
        FUNCTIONS.put("invert", "invert(colour) - the opposite colour");
        FUNCTIONS.put("spin", "spin(colour, degrees) - turned around the colour wheel");
        FUNCTIONS.put("contrast", "contrast(colour[, dark, light]) - whichever of dark / light reads best on it");
        FUNCTIONS.put("gradient", "gradient(top, bottom) - for background and boot");
    }

    private static final Map<String, Color> NAMED = new LinkedHashMap<>();

    static {
        NAMED.put("white", Color.WHITE);
        NAMED.put("black", Color.BLACK);
        NAMED.put("transparent", new Color(0, 0, 0, 0));
        NAMED.put("red", new Color(0xE53935));
        NAMED.put("orange", new Color(0xFB8C00));
        NAMED.put("yellow", new Color(0xFDD835));
        NAMED.put("green", new Color(0x43A047));
        NAMED.put("teal", new Color(0x00897B));
        NAMED.put("cyan", new Color(0x00ACC1));
        NAMED.put("blue", new Color(0x1E88E5));
        NAMED.put("indigo", new Color(0x3949AB));
        NAMED.put("purple", new Color(0x8E24AA));
        NAMED.put("pink", new Color(0xD81B60));
        NAMED.put("brown", new Color(0x6D4C41));
        NAMED.put("grey", new Color(0x9E9E9E));
        NAMED.put("gray", new Color(0x9E9E9E));
    }

    /** A mistake in a theme, with where it is (1-based). */
    public static final class Error extends Exception {
        public final int line, column;

        Error(int line, int column, String message) {
            super(message);
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return "line " + line + ", column " + column + ": " + getMessage();
        }
    }

    /**
     * A theme, worked out.
     *
     * @param colours  every property's colour (all of {@link #PROPERTIES})
     * @param consoles tile colours by console id (only the ones the theme changes)
     * @param fonts    font families to try, in order (empty: WII-UU's own choice)
     * @param dark     whether it is a dark theme (from base, or from how dark the background is)
     * @param followsSystem whether it depends on the computer being in dark mode (base auto)
     */
    public record Result(String name, String author, Map<String, Color> colours, Map<String, Color> consoles,
                         List<String> fonts, boolean dark, boolean followsSystem, List<String> warnings) {}

    // ---- values ---------------------------------------------------------------------------------

    private record Num(double value, boolean percent) {
        /** As a fraction: 40% and 0.4 are both 0.4. */
        double fraction() {
            return percent ? value / 100 : value;
        }
    }

    private record Gradient(Color top, Color bottom) {}

    // ---- tokens ---------------------------------------------------------------------------------

    private enum T { IDENT, STRING, HEX, NUMBER, PUNCT, NEWLINE, END }

    private record Tok(T type, String text, int line, int col) {}

    private static List<Tok> lex(String src) throws Error {
        List<Tok> out = new ArrayList<>();
        int i = 0, line = 1, lineStart = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            int col = i - lineStart + 1;
            if (c == '\n') {
                out.add(new Tok(T.NEWLINE, "\n", line, col));
                i++;
                line++;
                lineStart = i;
            } else if (c == ' ' || c == '\t' || c == '\r' || c == ';') {
                if (c == ';') out.add(new Tok(T.NEWLINE, ";", line, col));
                i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/' || c == '#' && (i + 1 >= n || !isHex(src.charAt(i + 1)) || isComment(src, i))) {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '#') {
                int s = i++;
                while (i < n && Character.isLetterOrDigit(src.charAt(i))) i++;
                out.add(new Tok(T.HEX, src.substring(s, i), line, col));
            } else if (c == '"') {
                StringBuilder b = new StringBuilder();
                i++;
                while (true) {
                    if (i >= n || src.charAt(i) == '\n') throw new Error(line, col, "this text has no closing \"");
                    char d = src.charAt(i++);
                    if (d == '"') break;
                    if (d == '\\' && i < n) d = src.charAt(i++);
                    b.append(d);
                }
                out.add(new Tok(T.STRING, b.toString(), line, col));
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))
                    || c == '-' && i + 1 < n && (Character.isDigit(src.charAt(i + 1)) || src.charAt(i + 1) == '.')) {
                int s = i++;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                if (i < n && Character.isLetter(src.charAt(i)) && Character.isDigit(c)) {     // a name like 3ds
                    while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '-' || src.charAt(i) == '_')) i++;
                    out.add(new Tok(T.IDENT, src.substring(s, i).toLowerCase(Locale.ROOT), line, col));
                    continue;
                }
                if (i < n && src.charAt(i) == '%') i++;
                out.add(new Tok(T.NUMBER, src.substring(s, i), line, col));
            } else if (Character.isLetter(c) || c == '_') {
                int s = i++;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '-' || src.charAt(i) == '_')) i++;
                out.add(new Tok(T.IDENT, src.substring(s, i).toLowerCase(Locale.ROOT), line, col));
            } else if ("(),={}*".indexOf(c) >= 0) {
                out.add(new Tok(T.PUNCT, String.valueOf(c), line, col));
                i++;
            } else {
                throw new Error(line, col, "unexpected '" + c + "'");
            }
        }
        out.add(new Tok(T.NEWLINE, "\n", line, i - lineStart + 1));
        out.add(new Tok(T.END, "", line, i - lineStart + 1));
        return out;
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /** "#abc" is a colour, "#about this" a comment (a hex word of the wrong length is a bad colour). */
    private static boolean isComment(String src, int at) {
        int i = at + 1;
        while (i < src.length() && Character.isLetterOrDigit(src.charAt(i))) i++;
        return !src.substring(at + 1, i).chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
    }

    // ---- evaluating -----------------------------------------------------------------------------

    private final List<Tok> toks;
    private int p;
    private final Function<Boolean, Map<String, Color>> builtIn;
    private final Function<String, Color> consoleColour;
    private final java.util.Set<String> consoleIds;
    private final boolean systemDark;

    private String name, author, base;
    private Map<String, Color> colours;
    private final Map<String, Color> consoles = new LinkedHashMap<>();
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private final List<String> fonts = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean anyColour;
    private Color it;                                  // inside "consoles = ...": that console's colour

    private ThemeScript(List<Tok> toks, Function<Boolean, Map<String, Color>> builtIn, Function<String, Color> consoleColour,
                        java.util.Set<String> consoleIds, boolean systemDark) {
        this.toks = toks;
        this.builtIn = builtIn;
        this.consoleColour = consoleColour;
        this.consoleIds = consoleIds;
        this.systemDark = systemDark;
    }

    /**
     * Works a theme out.
     *
     * @param source        the theme's text
     * @param fallbackName  its name if it doesn't say one (the file's name)
     * @param builtIn       WII-UU's own colours, light (false) or dark (true), with every property
     * @param consoleColour a console's own tile colour by id (null if there is no such console)
     * @param consoleIds    every console id
     * @param systemDark    whether the computer is in dark mode now (for base auto)
     */
    public static Result evaluate(String source, String fallbackName, Function<Boolean, Map<String, Color>> builtIn,
                                  Function<String, Color> consoleColour, java.util.Set<String> consoleIds,
                                  boolean systemDark) throws Error {
        ThemeScript s = new ThemeScript(lex(source), builtIn, consoleColour, consoleIds, systemDark);
        s.name = fallbackName;
        s.base = "light";
        s.colours = new LinkedHashMap<>(builtIn.apply(false));
        s.block(false);
        Map<String, Color> c = s.colours;
        boolean dark;
        if (s.base.equals("auto")) dark = systemDark;
        else if (!s.anyColour) dark = s.base.equals("dark");
        else dark = lum(mix(c.get("background-top"), c.get("background-bottom"), 0.5)) < 0.35;
        double readable = contrastRatio(c.get("text"), c.get("card"));
        if (readable < 3) s.warnings.add(String.format(Locale.ROOT, "text is hard to read on card (contrast %.1f:1, aim for 4.5:1)", readable));
        double onBg = contrastRatio(c.get("text"), mix(c.get("background-top"), c.get("background-bottom"), 0.5));
        if (onBg < 3) s.warnings.add(String.format(Locale.ROOT, "text is hard to read on the background (contrast %.1f:1)", onBg));
        return new Result(s.name, s.author, c, s.consoles, s.fonts, dark, s.base.equals("auto"), s.warnings);
    }

    /** The name a theme gives itself (for lists), without working it out; null if it has none. */
    public static String nameOf(String source) {
        try {
            List<Tok> t = lex(source);
            for (int i = 0; i + 1 < t.size(); i++) {
                if (t.get(i).type == T.IDENT && t.get(i).text.equals("theme") && t.get(i + 1).type == T.STRING
                        && (i == 0 || t.get(i - 1).type == T.NEWLINE)) return t.get(i + 1).text;
            }
        } catch (Error ignored) {
            // shown when it is chosen
        }
        return null;
    }

    private Tok peek() {
        return toks.get(p);
    }

    private Tok next() {
        return toks.get(p++);
    }

    private boolean at(String punct) {
        Tok t = peek();
        return t.type == T.PUNCT && t.text.equals(punct);
    }

    private Tok expect(String punct, String what) throws Error {
        Tok t = next();
        if (t.type != T.PUNCT || !t.text.equals(punct)) throw err(t, "expected " + what + (t.type == T.NEWLINE ? " before the end of the line" : ", not " + show(t)));
        return t;
    }

    private void endOfLine() throws Error {
        Tok t = peek();
        if (t.type == T.NEWLINE || t.type == T.END || at("}")) {
            if (t.type == T.NEWLINE) p++;
            return;
        }
        throw err(t, "expected the end of the line, not " + show(t));
    }

    private static String show(Tok t) {
        return switch (t.type) {
            case NEWLINE -> "the end of the line";
            case END -> "the end of the theme";
            case STRING -> "\"" + t.text + "\"";
            default -> "'" + t.text + "'";
        };
    }

    private static Error err(Tok t, String message) {
        return new Error(t.line, t.col, message);
    }

    /** Statements until the end (or a closing brace, inside "when"); {@code skip}: parse only. */
    private void block(boolean inside) throws Error {
        while (true) {
            Tok t = peek();
            if (t.type == T.NEWLINE) {
                p++;
                continue;
            }
            if (t.type == T.END) {
                if (inside) throw err(t, "a { is never closed with }");
                return;
            }
            if (at("}")) {
                if (!inside) throw err(t, "this } has no { before it");
                return;
            }
            statement();
        }
    }

    private void statement() throws Error {
        Tok t = next();
        if (t.type != T.IDENT) throw err(t, "expected a setting (like accent = #00A8E8), not " + show(t));
        switch (t.text) {
            case "theme" -> {
                name = string("the theme's name in quotes");
                endOfLine();
            }
            case "by", "author" -> {
                author = string("who made it, in quotes");
                endOfLine();
            }
            case "base" -> {
                Tok b = next();
                if (b.type != T.IDENT || !List.of("light", "dark", "auto").contains(b.text))
                    throw err(b, "base is light, dark or auto, not " + show(b));
                if (anyColour) throw err(t, "base has to come before any colours (it starts them over)");
                base = b.text;
                colours = new LinkedHashMap<>(builtIn.apply(b.text.equals("auto") ? systemDark : b.text.equals("dark")));
                endOfLine();
            }
            case "font" -> {
                do fonts.add(string("a font's name in quotes")); while (take(","));
                endOfLine();
            }
            case "let" -> {
                Tok v = next();
                if (v.type != T.IDENT) throw err(v, "expected a name after let, not " + show(v));
                if (PROPERTIES.containsKey(v.text) || PAIRS.containsKey(v.text) || FUNCTIONS.containsKey(v.text))
                    throw err(v, "'" + v.text + "' is already taken by WII-UU; pick another name");
                expect("=", "=");
                vars.put(v.text, expr());
                endOfLine();
            }
            case "when" -> {
                Tok m = next();
                if (m.type != T.IDENT || !m.text.equals("dark") && !m.text.equals("light"))
                    throw err(m, "when dark { ... } or when light { ... }, not " + show(m));
                expect("{", "{");
                boolean dark = base.equals("auto") ? systemDark : base.equals("dark");
                if (dark == m.text.equals("dark")) block(true);
                else skipBlock();
                expect("}", "}");
                endOfLine();
            }
            case "console" -> {
                Tok id = next();
                if (id.type != T.IDENT || !consoleIds.contains(id.text))
                    throw err(id, "there is no console '" + id.text + "'" + suggest(id.text, consoleIds));
                expect("=", "=");
                Tok value = peek();
                consoles.put(id.text, colour(expr(), value));
                endOfLine();
            }
            case "consoles" -> {
                expect("=", "=");
                int start = p;
                for (String id : consoleIds) {
                    p = start;
                    it = consoles.getOrDefault(id, consoleColour.apply(id));
                    consoles.put(id, colour(expr(), toks.get(start)));
                }
                it = null;
                endOfLine();
            }
            default -> {
                if (!PROPERTIES.containsKey(t.text) && !PAIRS.containsKey(t.text)) {
                    List<String> known = new ArrayList<>(PROPERTIES.keySet());
                    known.addAll(PAIRS.keySet());
                    known.addAll(List.of("theme", "by", "base", "font", "let", "when", "console", "consoles"));
                    throw err(t, "there is no setting '" + t.text + "'" + suggest(t.text, known));
                }
                expect("=", "=");
                Tok value = peek();
                Object v = expr();
                String[] pair = PAIRS.get(t.text);
                if (pair != null) {
                    Gradient g = v instanceof Gradient gr ? gr : new Gradient(colour(v, value), colour(v, value));
                    colours.put(pair[0], g.top);
                    colours.put(pair[1], g.bottom);
                } else {
                    colours.put(t.text, colour(v, value));
                }
                anyColour = true;
                endOfLine();
            }
        }
    }

    private boolean take(String punct) {
        if (!at(punct)) return false;
        p++;
        return true;
    }

    private String string(String what) throws Error {
        Tok t = next();
        if (t.type != T.STRING) throw err(t, "expected " + what + ", not " + show(t));
        return t.text;
    }

    private void skipBlock() throws Error {
        int depth = 0;
        while (true) {
            Tok t = peek();
            if (t.type == T.END) throw err(t, "a { is never closed with }");
            if (at("{")) depth++;
            if (at("}")) {
                if (depth == 0) return;
                depth--;
            }
            p++;
        }
    }

    private Color colour(Object v, Tok where) throws Error {
        if (v instanceof Color c) return c;
        if (v instanceof Gradient) throw err(where, "a gradient only works for background and boot");
        throw err(where, "expected a colour here, not a number");
    }

    private Object expr() throws Error {
        Tok t = next();
        switch (t.type) {
            case HEX -> {
                return hex(t);
            }
            case NUMBER -> {
                boolean pct = t.text.endsWith("%");
                try {
                    return new Num(Double.parseDouble(pct ? t.text.substring(0, t.text.length() - 1) : t.text), pct);
                } catch (NumberFormatException e) {
                    throw err(t, "'" + t.text + "' isn't a number");
                }
            }
            case IDENT -> {
                if (at("(")) return call(t);
                return lookup(t);
            }
            default -> throw err(t, "expected a colour (like #00A8E8, white or mix(a, b)), not " + show(t));
        }
    }

    private Object lookup(Tok t) throws Error {
        String n = t.text;
        if (n.equals("it")) {
            if (it == null) throw err(t, "'it' only means something in consoles = ... (each console's own colour)");
            return it;
        }
        if (n.equals("none")) return new Color(0, 0, 0, 0);
        if (vars.containsKey(n)) return vars.get(n);
        if (colours.containsKey(n)) return colours.get(n);
        if (PAIRS.containsKey(n)) return new Gradient(colours.get(PAIRS.get(n)[0]), colours.get(PAIRS.get(n)[1]));
        if (consoleIds.contains(n)) return consoles.getOrDefault(n, consoleColour.apply(n));
        if (NAMED.containsKey(n)) return NAMED.get(n);
        List<String> known = new ArrayList<>(vars.keySet());
        known.addAll(colours.keySet());
        known.addAll(NAMED.keySet());
        known.addAll(consoleIds);
        throw err(t, "'" + n + "' isn't a colour, a setting or a let" + suggest(n, known));
    }

    private Object call(Tok fn) throws Error {
        expect("(", "(");
        List<Object> args = new ArrayList<>();
        List<Tok> at = new ArrayList<>();
        if (!this.at(")")) {
            do {
                at.add(peek());
                args.add(expr());
            } while (take(","));
        }
        expect(")", ") to close " + fn.text + "(");
        String f = fn.text;
        if (!FUNCTIONS.containsKey(f)) throw err(fn, "there is no function '" + f + "'" + suggest(f, FUNCTIONS.keySet()));
        Args a = new Args(fn, args, at);
        return switch (f) {
            case "rgb" -> {
                a.count(3, 4);
                yield new Color(channel(a.num(0)), channel(a.num(1)), channel(a.num(2)), args.size() > 3 ? unit(a.num(3)) : 255);
            }
            case "hsl" -> {
                a.count(3, 4);
                Color c = hsl(a.num(0).value / 360.0, a.num(1).fraction(), a.num(2).fraction());
                yield withAlpha(c, args.size() > 3 ? unit(a.num(3)) : 255);
            }
            case "mix" -> {
                a.count(2, 3);
                yield mix(a.col(0), a.col(1), args.size() > 2 ? a.num(2).fraction() : 0.5);
            }
            case "lighten" -> {
                a.count(2, 2);
                yield withAlpha(mix(a.col(0), Color.WHITE, a.num(1).fraction()), a.col(0).getAlpha());
            }
            case "darken" -> {
                a.count(2, 2);
                yield withAlpha(mix(a.col(0), Color.BLACK, a.num(1).fraction()), a.col(0).getAlpha());
            }
            case "alpha" -> {
                a.count(2, 2);
                yield withAlpha(a.col(0), unit(a.num(1)));
            }
            case "saturate", "desaturate" -> {
                a.count(2, 2);
                float[] h = Color.RGBtoHSB(a.col(0).getRed(), a.col(0).getGreen(), a.col(0).getBlue(), null);
                double d = a.num(1).fraction() * (f.equals("saturate") ? 1 : -1);
                yield withAlpha(Color.getHSBColor(h[0], (float) Math.max(0, Math.min(1, h[1] + d)), h[2]), a.col(0).getAlpha());
            }
            case "grey" -> {
                a.count(1, 1);
                Color c = a.col(0);
                int y = (int) Math.round(c.getRed() * 0.299 + c.getGreen() * 0.587 + c.getBlue() * 0.114);
                yield new Color(y, y, y, c.getAlpha());
            }
            case "invert" -> {
                a.count(1, 1);
                Color c = a.col(0);
                yield new Color(255 - c.getRed(), 255 - c.getGreen(), 255 - c.getBlue(), c.getAlpha());
            }
            case "spin" -> {
                a.count(2, 2);
                Color c = a.col(0);
                float[] h = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
                yield withAlpha(Color.getHSBColor((float) (h[0] + a.num(1).value / 360.0), h[1], h[2]), c.getAlpha());
            }
            case "contrast" -> {
                if (args.size() != 1 && args.size() != 3) throw err(fn, "contrast takes 1 or 3 values: " + FUNCTIONS.get(f));
                Color dark = args.size() == 3 ? a.col(1) : new Color(0x202124), light = args.size() == 3 ? a.col(2) : Color.WHITE;
                yield contrastRatio(dark, a.col(0)) >= contrastRatio(light, a.col(0)) ? dark : light;
            }
            case "gradient" -> {
                a.count(2, 2);
                yield new Gradient(a.col(0), a.col(1));
            }
            default -> throw err(fn, "there is no function '" + f + "'");
        };
    }

    /** A function's values, with errors that point at the right one. */
    private record Args(Tok fn, List<Object> values, List<Tok> at) {
        void count(int min, int max) throws Error {
            int n = values.size();
            if (n < min || n > max) {
                String want = min == max ? String.valueOf(min) : min + " or " + max;
                throw err(fn, fn.text + " takes " + want + " values, not " + n + ": " + FUNCTIONS.get(fn.text));
            }
        }

        Color col(int i) throws Error {
            if (values.get(i) instanceof Color c) return c;
            throw err(at.get(i), fn.text + " needs a colour here: " + FUNCTIONS.get(fn.text));
        }

        Num num(int i) throws Error {
            if (values.get(i) instanceof Num n) return n;
            throw err(at.get(i), fn.text + " needs a number here: " + FUNCTIONS.get(fn.text));
        }
    }

    private Color hex(Tok t) throws Error {
        String h = t.text.substring(1);
        if (h.length() == 3 || h.length() == 4) {
            StringBuilder b = new StringBuilder();
            for (char c : h.toCharArray()) b.append(c).append(c);
            h = b.toString();
        }
        if ((h.length() != 6 && h.length() != 8) || !h.chars().allMatch(c -> Character.digit(c, 16) >= 0))
            throw err(t, "'" + t.text + "' isn't a colour: use #RGB, #RRGGBB or #RRGGBBAA (comments start with '# ')");
        long v = Long.parseLong(h, 16);
        return h.length() == 6 ? new Color((int) v) : new Color((int) (v >> 24) & 255, (int) (v >> 16) & 255, (int) (v >> 8) & 255, (int) v & 255);
    }

    // ---- colour arithmetic ----------------------------------------------------------------------

    private static int channel(Num n) {
        return clamp(n.percent ? n.value * 2.55 : n.value);
    }

    private static int unit(Num n) {
        return clamp(n.fraction() * 255);
    }

    private static int clamp(double v) {
        return (int) Math.round(Math.max(0, Math.min(255, v)));
    }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color(clamp(a.getRed() + (b.getRed() - a.getRed()) * t), clamp(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                clamp(a.getBlue() + (b.getBlue() - a.getBlue()) * t), clamp(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color hsl(double h, double s, double l) {
        h = ((h % 1) + 1) % 1;
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        return new Color(clamp(hue(p, q, h + 1 / 3.0) * 255), clamp(hue(p, q, h) * 255), clamp(hue(p, q, h - 1 / 3.0) * 255));
    }

    private static double hue(double p, double q, double t) {
        t = ((t % 1) + 1) % 1;
        if (t < 1 / 6.0) return p + (q - p) * 6 * t;
        if (t < 1 / 2.0) return q;
        if (t < 2 / 3.0) return p + (q - p) * (2 / 3.0 - t) * 6;
        return p;
    }

    /** Relative luminance, 0 (black) to 1 (white). */
    static double lum(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    static double contrastRatio(Color a, Color b) {
        double x = lum(a), y = lum(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static String suggest(String word, java.util.Collection<String> known) {
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String k : known) {
            int d = distance(word, k);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        return best != null && bestD <= Math.max(2, word.length() / 3) ? " - did you mean '" + best + "'?" : "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
