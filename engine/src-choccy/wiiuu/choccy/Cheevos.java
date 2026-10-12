package wiiuu.choccy;

import java.util.ArrayList;
import java.util.List;

/**
 * RetroAchievements' achievement logic: parses an achievement's conditions ("MemAddr", like
 * {@code 0xH0010=5_d0xH0010=4}) and evaluates them against the game's memory once a frame.
 *
 * <p>Ported from rcheevos (https://github.com/RetroAchievements/rcheevos, MIT license): the same
 * operands and sizes, condition flags (PauseIf, ResetIf, AddSource, SubSource, AddHits, SubHits,
 * AndNext, OrNext, AddAddress, Measured, MeasuredIf, Trigger, Remember, ResetNextIf), hit counts,
 * alternative groups, delta and prior values, and the achievement states (an achievement has to be
 * false once before it can trigger, so nothing unlocks just because the game was loaded that way).
 * AddSource chains are worked out as they're evaluated rather than as rcheevos' combined memory
 * references; that gives the same numbers, only a "prior" of a whole chain isn't tracked.
 */
final class Cheevos {

    /** Reads {@code bytes} (1, 2 or 4) bytes, little-endian, at an achievement address; 0 where nothing is. */
    interface Memory {
        int peek(long address, int bytes);
    }

    // ---- sizes (in rcheevos' order) ----------------------------------------------------------------

    static final int S8 = 0, S16 = 1, S24 = 2, S32 = 3, LOW = 4, HIGH = 5, BIT0 = 6, BIT7 = 13, BITCOUNT = 14,
            S16BE = 15, S24BE = 16, S32BE = 17, FLOAT = 18, MBF32 = 19, MBF32LE = 20, FLOATBE = 21, DOUBLE32 = 22,
            DOUBLE32BE = 23;
    private static final int[] MASK = {0xff, 0xffff, 0xffffff, 0xffffffff, 0x0f, 0xf0, 0x01, 0x02, 0x04, 0x08, 0x10, 0x20,
            0x40, 0x80, 0xff, 0xffff, 0xffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff, 0xffffffff};
    private static final int[] BYTES = {1, 2, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 4, 4, 4, 4, 4, 4, 4, 4};

    static boolean isFloat(int size) {
        return size >= FLOAT;
    }

    // ---- values: unsigned / signed 32-bit integers and floats, as rcheevos combines them ------------

    static final int NONE = 0, U = 1, I = 2, F = 3;

    static final class V {
        int type;
        int n;                                     // u32 or i32 bits
        float f;

        V set(V o) {
            type = o.type;
            n = o.n;
            f = o.f;
            return this;
        }

        V u(int v) {
            type = U;
            n = v;
            return this;
        }

        V fl(float v) {
            type = F;
            f = v;
            return this;
        }

        void convert(int to) {
            if (to == type) return;
            switch (to) {
                case U, I -> n = switch (type) {
                    case U, I -> n;
                    case F -> to == U ? (int) (long) f : (int) f;
                    default -> 0;
                };
                case F -> f = switch (type) {
                    case U -> (float) Integer.toUnsignedLong(n);
                    case I -> (float) n;
                    default -> 0f;
                };
                default -> { }
            }
            type = to;
        }

        void negate() {
            if (type == U) type = I;
            if (type == I) n = -n;
            else if (type == F) f = -f;
        }

        void add(V a) {
            V b = a;
            if (b.type != type && type != NONE) {
                if (b.type == F) convert(F);
                else {
                    b = new V().set(a);
                    b.convert(type);
                }
            }
            switch (type) {
                case U, I -> n += b.n;
                case F -> f += b.f;
                case NONE -> set(b);
                default -> { }
            }
        }

        void combine(V b, int oper) {
            switch (oper) {
                case MULT -> {
                    if (b.type == F || type == F) {
                        convert(F);
                        V c = new V().set(b);
                        c.convert(F);
                        f *= c.f;
                    } else if (type == NONE || b.type == NONE) {
                        type = NONE;
                    } else {
                        n *= b.n;
                    }
                }
                case DIV, MOD -> {
                    if (b.type == NONE || (b.type != F && b.n == 0) || (b.type == F && b.f == 0f)) {
                        type = NONE;
                        return;
                    }
                    if (b.type == F || type == F) {
                        convert(F);
                        V c = new V().set(b);
                        c.convert(F);
                        f = oper == DIV ? f / c.f : (float) (f % c.f);
                    } else if (type == U) {
                        n = oper == DIV ? Integer.divideUnsigned(n, b.n) : Integer.remainderUnsigned(n, b.n);
                    } else if (type == I) {
                        n = oper == DIV ? n / b.n : n % b.n;
                    }
                }
                case AND, XOR -> {
                    convert(U);
                    V c = new V().set(b);
                    c.convert(U);
                    n = oper == AND ? n & c.n : n ^ c.n;
                }
                case ADD -> add(b);
                case SUB -> {
                    V c = new V().set(b);
                    c.negate();
                    add(c);
                }
                default -> { }
            }
        }

        static boolean compare(V a, V b, int oper) {
            if (a.type != b.type) {
                if (b.type == F) {
                    a = new V().set(a);
                    a.convert(F);
                } else {
                    b = new V().set(b);
                    b.convert(a.type);
                }
            }
            int c;
            switch (a.type) {
                case U -> c = Integer.compareUnsigned(a.n, b.n);
                case I -> c = Integer.compare(a.n, b.n);
                case F -> {
                    float f1 = a.f, f2 = b.f;
                    if (f1 == f2) c = 0;
                    else {
                        float th = Math.min(Math.abs(f1), Math.abs(f2)) * Math.ulp(1f), d = f1 - f2;
                        c = Math.abs(d) <= th ? 0 : d > 0 ? 1 : -1;
                    }
                }
                default -> c = 0;
            }
            return switch (oper) {
                case EQ -> c == 0;
                case NE -> c != 0;
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                default -> true;
            };
        }
    }

    // ---- operators -------------------------------------------------------------------------------------

    static final int EQ = 0, LT = 1, LE = 2, GT = 3, GE = 4, NE = 5, NOP = 6, MULT = 7, DIV = 8, AND = 9, XOR = 10, MOD = 11,
            ADD = 12, SUB = 13;

    // ---- memory references: a value read once a frame, with what it was before ------------------------

    /** The address of an AddAddress chain: the value of the condition before, plus the offset. */
    private record Pointer(Operand a, int oper, Operand b) {}

    static final class Ref {
        final long address;
        final int size;
        final Pointer pointer;                     // AddAddress: address is relative to this
        int value, prior;
        boolean changed;

        Ref(long address, int size, Pointer pointer) {
            this.address = address;
            this.size = size;
            this.pointer = pointer;
        }

        void update(Memory m, Trigger t) {
            long at = address;
            if (pointer != null) {
                V v = new V();
                t.value(pointer.a, v);
                if (pointer.oper != NOP) {
                    V w = new V();
                    t.value(pointer.b, w);
                    v.combine(w, pointer.oper);
                }
                v.convert(U);
                at = (Integer.toUnsignedLong(v.n) + address) & 0xffffffffL;
            }
            int now = m.peek(at, BYTES[size]) & MASK[size];
            if (now != value) {
                prior = value;
                value = now;
                changed = true;
            } else {
                changed = false;
            }
        }
    }

    // ---- operands --------------------------------------------------------------------------------------

    static final int CONST = 0, FP = 1, ADDRESS = 2, DELTA = 3, PRIOR = 4, BCD = 5, INVERTED = 6, RECALL = 7;

    static final class Operand {
        int kind, size = S32;
        int num;
        float dbl;
        Ref ref;
    }

    // ---- conditions ------------------------------------------------------------------------------------

    static final int STANDARD = 0, PAUSE_IF = 1, RESET_IF = 2, MEASURED_IF = 3, TRIGGER = 4, MEASURED = 5, ADD_SOURCE = 6,
            SUB_SOURCE = 7, ADD_ADDRESS = 8, REMEMBER = 9, ADD_HITS = 10, SUB_HITS = 11, RESET_NEXT_IF = 12, AND_NEXT = 13,
            OR_NEXT = 14;

    static final class Cond {
        int type;
        Operand a, b;
        int oper = NOP;
        int required, hits;
        boolean modifying() {
            return type == ADD_SOURCE || type == SUB_SOURCE || type == ADD_ADDRESS || type == REMEMBER;
        }
    }

    /** One group of conditions (the core group or an alternative), in the order rcheevos evaluates them. */
    static final class Group {
        final List<Cond> all = new ArrayList<>();
        final List<List<Cond>> pause = new ArrayList<>(), reset = new ArrayList<>(), hitTarget = new ArrayList<>(),
                measured = new ArrayList<>(), other = new ArrayList<>();
        boolean paused;

        /** Sorts the conditions into chains (the combining ones with the one they lead to), by kind. */
        void classify() {
            List<Cond> chain = new ArrayList<>();
            for (Cond c : all) {
                chain.add(c);
                switch (c.type) {
                    case ADD_SOURCE, SUB_SOURCE, ADD_ADDRESS, ADD_HITS, SUB_HITS, AND_NEXT, OR_NEXT, REMEMBER, RESET_NEXT_IF -> {
                        continue;
                    }
                    case PAUSE_IF -> pause.add(chain);
                    case RESET_IF -> reset.add(chain);
                    case MEASURED, MEASURED_IF -> measured.add(chain);
                    default -> (c.required != 0 ? hitTarget : other).add(chain);
                }
                chain = new ArrayList<>();
            }
            if (!chain.isEmpty()) other.add(chain);
        }

        void resetHits() {
            for (Cond c : all) c.hits = 0;
        }
    }

    // ---- an achievement ----------------------------------------------------------------------------------

    static final int WAITING = 0, ACTIVE = 1, PAUSED = 2, PRIMED = 3, TRIGGERED = 4;

    static final class Trigger {
        final Group core;
        final List<Group> alts = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();
        int state = WAITING;
        boolean hadHits;
        // evaluation state
        private boolean isTrue, isPrimed, isPaused, wasReset, hasHits, andNext, orNext, resetNext, stop;
        private int addHits;
        private final V recall = new V(), acc = new V();

        Trigger(Group core) {
            this.core = core;
        }

        /** Reads memory and evaluates; true the one frame the achievement triggers. */
        boolean frame(Memory m) {
            for (Ref r : refs) r.update(m, this);
            if (state == TRIGGERED) return false;
            wasReset = false;
            hasHits = false;
            boolean ret = test(core);
            boolean paused = isPaused, primed = isPrimed;
            if (!alts.isEmpty()) {
                boolean sub = false, subPaused = true, subPrimed = false;
                for (Group g : alts) {
                    sub |= test(g);
                    subPaused &= isPaused;
                    subPrimed |= isPrimed;
                }
                ret &= sub;
                primed &= subPrimed;
                paused |= subPaused;
            }
            if (wasReset) {
                core.resetHits();
                for (Group g : alts) g.resetHits();
                if (state == PRIMED) state = ACTIVE;
                hadHits = false;
                return false;
            }
            if (ret) {
                if (state == WAITING) {                    // true from the start: must be false once first
                    core.resetHits();
                    for (Group g : alts) g.resetHits();
                    hadHits = false;
                    return false;
                }
                state = TRIGGERED;
                return true;
            }
            hadHits = hasHits;
            state = paused ? PAUSED : primed ? PRIMED : ACTIVE;
            return false;
        }

        private boolean test(Group g) {
            isTrue = true;
            isPrimed = true;
            isPaused = false;
            addHits = 0;
            andNext = true;
            orNext = false;
            resetNext = false;
            stop = false;
            if (!g.pause.isEmpty()) {
                run(g.pause, true);
                g.paused = isPaused;
                if (isPaused) return false;
            }
            run(g.reset, false);
            if (!wasReset) run(g.hitTarget, false);
            if (wasReset) for (List<Cond> chain : g.measured) for (Cond c : chain) c.hits = 0;
            run(g.measured, false);
            run(g.other, false);
            return isTrue;
        }

        private void run(List<List<Cond>> chains, boolean shortCircuit) {
            for (List<Cond> chain : chains) {
                acc.type = NONE;
                for (Cond c : chain) {
                    evaluate(c);
                    if (stop && shortCircuit) return;
                }
            }
        }

        private void evaluate(Cond c) {
            switch (c.type) {
                case ADD_SOURCE, SUB_SOURCE -> {
                    V v = new V();
                    modified(c, v);
                    if (c.type == SUB_SOURCE) {
                        if (acc.type == NONE) acc.u(0);
                        v.negate();
                    }
                    acc.add(v);
                }
                case REMEMBER -> {
                    V v = new V();
                    modified(c, v);
                    recall.set(v);
                    acc.type = NONE;
                }
                case ADD_ADDRESS -> { }                // in the memory references already
                case STANDARD -> {
                    boolean ok = withHits(c);
                    isTrue &= ok;
                    isPrimed &= ok;
                }
                case PAUSE_IF -> {
                    boolean ok = withHits(c);
                    if (ok) {
                        isPaused = true;
                        isTrue = isPrimed = false;
                        stop = true;
                    } else if (c.required == 0) {
                        c.hits = 0;
                    }
                }
                case RESET_IF -> {
                    if (withHits(c)) {
                        isTrue = isPrimed = false;
                        wasReset = true;
                        stop = true;
                    }
                }
                case TRIGGER -> isTrue &= withHits(c);
                case MEASURED -> {
                    if (c.required == 0) {
                        boolean ok = withHits(c);
                        isTrue &= ok;
                        isPrimed &= ok;
                    } else {
                        noAddHits(c);
                        int total = Math.max(0, c.hits + addHits);
                        addHits = 0;
                        boolean ok = Integer.compareUnsigned(total, c.required) >= 0;
                        isTrue &= ok;
                        isPrimed &= ok;
                        resetNext = false;
                    }
                }
                case MEASURED_IF -> {
                    boolean ok = withHits(c);
                    isTrue &= ok;
                    isPrimed &= ok;
                }
                case ADD_HITS, SUB_HITS -> {
                    noAddHits(c);
                    addHits += c.type == ADD_HITS ? c.hits : -c.hits;
                    resetNext = false;
                }
                case RESET_NEXT_IF -> resetNext = noAddHits(c);
                case AND_NEXT -> andNext = noAddHits(c);
                case OR_NEXT -> orNext = noAddHits(c);
                default -> {
                    stop = true;
                    isTrue = isPrimed = false;
                }
            }
        }

        /** A condition's comparison, with the AddSource total before it. */
        private boolean compare(Cond c) {
            V a = new V();
            value(c.a, a);
            if (acc.type != NONE) {
                V t = new V().set(acc);
                t.add(a);
                a = t;
                acc.type = NONE;
            }
            V b = new V();
            value(c.b, b);
            return V.compare(a, b, c.oper);
        }

        /** AddSource / SubSource / Remember: the left operand (with the total so far) combined with the right. */
        private void modified(Cond c, V out) {
            value(c.a, out);
            if (c.type == REMEMBER && acc.type != NONE) {
                V t = new V().set(acc);
                t.add(out);
                out.set(t);
            }
            if (c.oper != NOP) {
                V b = new V();
                value(c.b, b);
                out.combine(b, c.oper);
            }
        }

        private boolean noAddHits(Cond c) {
            boolean valid = compare(c);
            if (resetNext) {
                c.hits = 0;
                valid = false;
            } else {
                valid &= andNext;
                valid |= orNext;
                if (valid) {
                    hasHits = true;
                    if (c.required == 0) {
                        c.hits++;
                    } else if (Integer.compareUnsigned(c.hits, c.required) < 0) {
                        c.hits++;
                        valid = c.hits == c.required;
                    }
                } else if (c.hits > 0) {
                    hasHits = true;
                    valid = c.hits == c.required;
                }
            }
            andNext = true;
            orNext = false;
            return valid;
        }

        private boolean withHits(Cond c) {
            boolean valid = noAddHits(c);
            if (addHits != 0 && c.required != 0) {
                int total = Math.max(0, c.hits + addHits);
                valid = Integer.compareUnsigned(total, c.required) >= 0;
            }
            addHits = 0;
            resetNext = false;
            return valid;
        }

        /** An operand's value now. */
        void value(Operand o, V out) {
            switch (o.kind) {
                case CONST -> {
                    out.u(o.num);
                    return;
                }
                case FP -> {
                    out.fl(o.dbl);
                    return;
                }
                case RECALL -> {
                    if (recall.type == NONE) out.u(0);
                    else out.set(recall);
                    return;
                }
                default -> { }
            }
            Ref r = o.ref;
            int raw = switch (o.kind) {
                case DELTA -> r.changed ? r.prior : r.value;
                case PRIOR -> r.prior;
                default -> r.value;
            };
            transform(raw, o, out);
        }
    }

    /** A memory value in its size and format (then BCD / inverted), as rcheevos reads it. */
    static void transform(int v, Operand o, V out) {
        switch (o.size) {
            case S8 -> v &= 0xff;
            case S16 -> v &= 0xffff;
            case S24 -> v &= 0xffffff;
            case LOW -> v &= 0x0f;
            case HIGH -> v = (v >>> 4) & 0x0f;
            case BITCOUNT -> v = Integer.bitCount(v & 0xff);
            case S16BE -> v = ((v & 0xff00) >>> 8) | ((v & 0xff) << 8);
            case S24BE -> v = ((v & 0xff0000) >>> 16) | (v & 0xff00) | ((v & 0xff) << 16);
            case S32BE -> v = Integer.reverseBytes(v);
            case FLOAT -> {
                out.fl(Float.intBitsToFloat(v));
                return;
            }
            case FLOATBE -> {
                out.fl(Float.intBitsToFloat(Integer.reverseBytes(v)));
                return;
            }
            case DOUBLE32, DOUBLE32BE -> {
                int hi = o.size == DOUBLE32 ? v : Integer.reverseBytes(v);
                out.fl((float) Double.longBitsToDouble(((long) hi) << 32));
                return;
            }
            case MBF32, MBF32LE -> {
                out.fl(mbf(v, o.size == MBF32));
                return;
            }
            default -> {
                if (o.size >= BIT0 && o.size <= BIT7) v = (v >>> (o.size - BIT0)) & 1;
            }
        }
        if (o.kind == BCD) {
            int r = 0, mul = 1;
            int digits = switch (o.size) {
                case S8 -> 2;
                case S16, S16BE -> 4;
                case S24, S24BE -> 6;
                case S32, S32BE -> 8;
                default -> 0;
            };
            if (digits > 0) {
                for (int i = 0; i < digits; i++, mul *= 10) r += ((v >>> (4 * i)) & 0x0f) * mul;
                v = r;
            }
        } else if (o.kind == INVERTED) {
            v ^= switch (o.size) {
                case LOW, HIGH -> 0x0f;
                case S8 -> 0xff;
                case S16, S16BE -> 0xffff;
                case S24, S24BE -> 0xffffff;
                case S32, S32BE -> 0xffffffff;
                default -> 0x01;
            };
        }
        out.u(v);
    }

    /** Microsoft Binary Format floats (as rcheevos decodes them): MBF32 big-endian, MBF32_LE little-endian. */
    private static float mbf(int v, boolean bigEndian) {
        int mantissa, exponent;
        boolean negative;
        if (bigEndian) {
            mantissa = ((v & 0xFF000000) >>> 24) | ((v & 0x00FF0000) >>> 8) | ((v & 0x00007F00) << 8);
            exponent = (v & 0xFF) - 129;
            negative = (v & 0x8000) != 0;
        } else {
            mantissa = v & 0x7FFFFF;
            exponent = (v >>> 24) - 129;
            negative = (v & 0x800000) != 0;
        }
        if (mantissa == 0 && exponent == -129) return negative ? -0f : 0f;
        double d = (mantissa | 0x800000) / (double) 0x800000 * Math.pow(2, exponent);
        return (float) (negative ? -d : d);
    }

    // ---- parsing -------------------------------------------------------------------------------------------

    /** Parses an achievement's MemAddr. */
    static Trigger parse(String memaddr) {
        Parser p = new Parser(memaddr);
        Group core = p.group();
        Trigger t = new Trigger(core);
        while (p.at < p.s.length() && (p.s.charAt(p.at) == 'S' || p.s.charAt(p.at) == 's')) {
            p.at++;
            t.alts.add(p.group());
        }
        if (p.at != p.s.length()) throw new IllegalArgumentException("unexpected '" + p.s.charAt(p.at) + "' at " + p.at);
        t.refs.addAll(p.refs);
        core.classify();
        for (Group g : t.alts) g.classify();
        return t;
    }

    private static final class Parser {
        final String s;
        int at;
        final List<Ref> refs = new ArrayList<>();
        Pointer pointer;                              // AddAddress before this condition

        Parser(String s) {
            this.s = s;
        }

        char peek(int ahead) {
            return at + ahead < s.length() ? s.charAt(at + ahead) : '\0';
        }

        Group group() {
            Group g = new Group();
            if (at >= s.length() || peek(0) == 'S' && !(peek(1) == ':' )) {
                return g;                              // an empty core group ("S...")
            }
            while (true) {
                g.all.add(condition());
                if (peek(0) == '_') {
                    at++;
                    continue;
                }
                return g;
            }
        }

        Cond condition() {
            Cond c = new Cond();
            if (peek(0) != '\0' && peek(1) == ':') {
                c.type = switch (Character.toUpperCase(peek(0))) {
                    case 'P' -> PAUSE_IF;
                    case 'R' -> RESET_IF;
                    case 'A' -> ADD_SOURCE;
                    case 'B' -> SUB_SOURCE;
                    case 'C' -> ADD_HITS;
                    case 'D' -> SUB_HITS;
                    case 'N' -> AND_NEXT;
                    case 'O' -> OR_NEXT;
                    case 'M', 'G' -> MEASURED;
                    case 'Q' -> MEASURED_IF;
                    case 'I' -> ADD_ADDRESS;
                    case 'T' -> TRIGGER;
                    case 'K' -> REMEMBER;
                    case 'Z' -> RESET_NEXT_IF;
                    default -> throw new IllegalArgumentException("unknown condition type '" + peek(0) + "'");
                };
                at += 2;
            }
            Pointer via = pointer;
            c.a = operand(via);
            c.oper = operator();
            if (c.oper == NOP) {
                c.b = constant(c.modifying() ? 0 : 1);
            } else {
                if (c.modifying() && c.oper <= NE) c.oper = NOP;    // a comparison left over on AddSource & co: ignored
                c.b = operand(via);
                if (c.oper == NOP) c.b = constant(0);
                if (peek(0) == '(' || peek(0) == '.') {
                    char close = peek(0) == '(' ? ')' : '.';
                    int end = s.indexOf(close, at + 1);
                    if (end < 0) throw new IllegalArgumentException("unfinished hit count");
                    c.required = (int) Long.parseLong(s.substring(at + 1, end));
                    at = end + 1;
                    if (c.oper == NOP) c.required = 0;
                }
            }
            pointer = c.type == ADD_ADDRESS ? new Pointer(c.a, c.oper, c.b) : null;
            return c;
        }

        Operand constant(int v) {
            Operand o = new Operand();
            o.kind = CONST;
            o.num = v;
            return o;
        }

        int operator() {
            char c = peek(0);
            switch (c) {
                case '=':
                    at++;
                    if (peek(0) == '=') at++;
                    return EQ;
                case '!':
                    if (peek(1) == '=') {
                        at += 2;
                        return NE;
                    }
                    throw new IllegalArgumentException("bad operator at " + at);
                case '<':
                    if (peek(1) == '=') {
                        at += 2;
                        return LE;
                    }
                    at++;
                    return LT;
                case '>':
                    if (peek(1) == '=') {
                        at += 2;
                        return GE;
                    }
                    at++;
                    return GT;
                case '*': at++; return MULT;
                case '/': at++; return DIV;
                case '&': at++; return AND;
                case '^': at++; return XOR;
                case '%': at++; return MOD;
                case '+': at++; return ADD;
                case '-': at++; return SUB;
                case '\0', '_', 'S', ')', '$': return NOP;
                default: throw new IllegalArgumentException("bad operator '" + c + "' at " + at);
            }
        }

        Operand operand(Pointer via) {
            char c = peek(0);
            Operand o = new Operand();
            switch (c) {
                case 'h', 'H' -> {
                    at++;
                    o.kind = CONST;
                    o.num = (int) Math.min(0xffffffffL, number(16));
                    return o;
                }
                case 'f', 'F' -> {
                    if (Character.isLetter(peek(1))) return memory(o, via);
                    at++;
                    o.kind = FP;
                    int start = at;
                    if (peek(0) == '-' || peek(0) == '+') at++;
                    while (Character.isDigit(peek(0)) || peek(0) == '.') at++;
                    o.dbl = Float.parseFloat(s.substring(start, at));
                    return o;
                }
                case 'v', 'V', '+', '-' -> {
                    if (c == 'v' || c == 'V') at++;
                    boolean negative = peek(0) == '-';
                    if (peek(0) == '-' || peek(0) == '+') at++;
                    long v = Math.min(0x7fffffffL, number(10));
                    o.kind = CONST;
                    o.num = negative ? (int) -v : (int) v;
                    return o;
                }
                case '{' -> {
                    int end = s.indexOf('}', at);
                    if (end < 0 || !s.substring(at + 1, end).equals("recall")) throw new IllegalArgumentException("unknown variable");
                    at = end + 1;
                    o.kind = RECALL;
                    return o;
                }
                case '0' -> {
                    if (peek(1) == 'x' || peek(1) == 'X') return memory(o, via);
                    o.kind = CONST;
                    o.num = (int) Math.min(0xffffffffL, number(10));
                    return o;
                }
                default -> {
                    if (Character.isDigit(c)) {
                        o.kind = CONST;
                        o.num = (int) Math.min(0xffffffffL, number(10));
                        return o;
                    }
                    return memory(o, via);
                }
            }
        }

        Operand memory(Operand o, Pointer via) {
            o.kind = switch (peek(0)) {
                case 'd', 'D' -> DELTA;
                case 'p', 'P' -> PRIOR;
                case 'b', 'B' -> BCD;
                case '~' -> INVERTED;
                default -> ADDRESS;
            };
            if (o.kind != ADDRESS) at++;
            if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
                at += 2;
                char z = peek(0);
                int size = switch (Character.toUpperCase(z)) {
                    case 'H' -> S8;
                    case ' ' -> S16;
                    case 'X' -> S32;
                    case 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T' -> BIT0 + (Character.toUpperCase(z) - 'M');
                    case 'L' -> LOW;
                    case 'U' -> HIGH;
                    case 'K' -> BITCOUNT;
                    case 'W' -> S24;
                    case 'G' -> S32BE;
                    case 'I' -> S16BE;
                    case 'J' -> S24BE;
                    default -> -1;
                };
                if (size < 0) {
                    if (Character.digit(z, 16) < 0) throw new IllegalArgumentException("bad memory size '" + z + "'");
                    size = S16;                         // no size letter: 16 bits (old style)
                } else {
                    at++;
                }
                o.size = size;
            } else if (peek(0) == 'f' || peek(0) == 'F') {
                at++;
                o.size = switch (Character.toUpperCase(peek(0))) {
                    case 'F' -> FLOAT;
                    case 'B' -> FLOATBE;
                    case 'H' -> DOUBLE32;
                    case 'I' -> DOUBLE32BE;
                    case 'M' -> MBF32;
                    case 'L' -> MBF32LE;
                    default -> throw new IllegalArgumentException("bad float size");
                };
                at++;
            } else {
                throw new IllegalArgumentException("bad operand at " + at);
            }
            long address = number(16);
            Ref r = new Ref(address, o.size, via);
            refs.add(r);
            o.ref = r;
            return o;
        }

        long number(int radix) {
            int start = at;
            while (Character.digit(peek(0), radix) >= 0) at++;
            if (start == at) throw new IllegalArgumentException("number expected at " + start);
            String digits = s.substring(start, at);
            return digits.length() > 10 ? 0xffffffffL : Math.min(0xffffffffL, Long.parseLong(digits, radix));
        }
    }
}
