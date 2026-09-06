package io.github.kartell58.cardinal;

import java.util.ArrayList;
import java.util.List;

/** JVM type descriptors -> Java source text (no imports; dotted names stay compilable). */
final class Types {

    private Types() {}

    /** Internal class name such as "java/lang/String" or "[I" or "[Ljava/lang/String;". */
    static String type(String internalName) {
        if (internalName.isEmpty()) return "";
        int dims = 0;
        while (internalName.startsWith("[")) {
            dims++;
            internalName = internalName.substring(1);
        }
        String base;
        char c = internalName.charAt(0);
        if (c == 'L') base = name(internalName.substring(1, internalName.length() - 1));
        else base = switch (c) {
            case 'V' -> "void";
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            default -> "Object";
        };
        return base + "[]".repeat(dims);
    }

    /** Field descriptor like "Ljava/lang/String;" -> "String". */
    static String field(String desc) {
        return type(desc);
    }

    /** Method descriptor like "(ILjava/lang/String;)V" -> [params, ret]. */
    static String[] method(String desc) {
        List<String> params = new ArrayList<>();
        int i = 0;
        int end = desc.indexOf(')', 1);
        i = 1;
        while (i < end) {
            char c = desc.charAt(i);
            if (c == '[') {
                int dims = 0;
                while (desc.charAt(i + dims) == '[') dims++;
                int j = i + dims;
                String base;
                if (desc.charAt(j) == 'L') {
                    int k = desc.indexOf(';', j);
                    base = Types.type(desc.substring(i, k + 1));
                    i = k + 1;
                } else {
                    base = Types.type(desc.substring(i, j + 1));
                    i = j + 1;
                }
                params.add(base);
                continue;
            }
            if (c == 'L') {
                int k = desc.indexOf(';', i);
                params.add(Types.type(desc.substring(i, k + 1)));
                i = k + 1;
            } else {
                params.add(Types.type(String.valueOf(c)));
                i++;
            }
        }
        List<String> result = new ArrayList<>(params);
        result.add(Types.type(desc.substring(end + 1)));
        return result.toArray(new String[0]);
    }

    /** Internal dotted name: "java/lang/String" -> "String"; others -> "a.b.C". */
    static String name(String internal) {
        String dotted = internal.replace('/', '.');
        return dotted.startsWith("java.lang.") || internal.startsWith("java/lang/")
                ? dotted.substring(dotted.lastIndexOf('.') + 1)
                : dotted;
    }

    /** Shortens common JDK functional/collection interface names for readability. */
    static String shortName(String internal) {
        String n = name(internal);
        if (n.startsWith("java.util.function.")) return n.substring("java.util.function.".length());
        if (n.startsWith("java.util.stream.")) return n.substring("java.util.stream.".length());
        if (n.startsWith("java.util.")) {
            if (n.equals("java.util.List") || n.equals("java.util.Set") || n.equals("java.util.Map")
                    || n.equals("java.util.Collection") || n.equals("java.util.Queue")
                    || n.equals("java.util.Deque") || n.equals("java.util.Iterator")
                    || n.equals("java.util.Comparator")) return n.substring("java.util.".length());
        }
        return n;
    }

    /** Local variable slot width: category 2 types occupy two slots. */
    static int width(String javaType) {
        return javaType.equals("long") || javaType.equals("double") ? 2 : 1;
    }

    /**
     * Renders a reference to the class {@code internal}: its {@code genericText} source (from a Signature)
     * when available, shortening to the last {@code .}/<code>$</code> segment for nested types, which are
     * declared inline in their top-level source file.
     */
    static String typeText(String internal, String genericText) {
        String n = name(internal);
        if (!internal.contains("$")) return genericText != null ? genericText : n;
        String t = genericText != null ? genericText : n;
        int lo = t.indexOf('<');
        String head = lo < 0 ? t : t.substring(0, lo);
        int cut = Math.max(head.lastIndexOf('.'), head.lastIndexOf('$'));
        if (cut >= 0) return t.substring(cut + 1);
        return t;
    }

    /** Renders an entry of the class-file Signature attribute (class/field form, e.g.
     * {@code Ljava/util/function/Function<Ljava/lang/String;Ljava/lang/String;>;}) as Java source.
     * Returns null when the signature is unparsable or uses constructs this renderer does not handle,
     * so callers can fall back to the erased descriptor.
     */
    static String sig(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) == '(') return null;
        try {
            P p = new P(s);
            String t = p.fieldType();
            return p.ok() ? t : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Method-form signature {@code (TT;...)Ljava/lang/Object;} -> [params..., return]. Returns null when unparsable.
     */
    static String[] sigMethod(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            P p = new P(s);
            while (p.peek() == '<') skipTypeParams(p);
            if (p.peek() != '(') return null;
            List<String> ps = new ArrayList<>();
            p.expect('(');
            while (p.peek() != ')') {
                if (p.end()) throw new RuntimeException();
                ps.add(p.fieldType());
            }
            p.expect(')');
            String r = p.fieldType();
            if (!p.ok()) throw new RuntimeException();
            List<String> out = new ArrayList<>(ps);
            out.add(r);
            return out.toArray(new String[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Renders every superclass/interface type of a class Signature (e.g. an anonymous class's),
     * ignoring its type parameters. Returns null when unparsable.
     */
    static String[] sigClassTypes(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            P p = new P(s);
            while (p.peek() == '<') skipTypeParams(p);
            List<String> out = new ArrayList<>();
            while (!p.ok()) out.add(p.classType());
            return out.toArray(new String[0]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Balances a {@code <...>} (may nest) starting at the current {@code <}. */
    private static void skipTypeParams(P p) {
        p.expect('<');
        int depth = 1;
        while (depth > 0) {
            if (p.end()) throw new RuntimeException();
            char c = (char) p.peek();
            p.i++;
            if (c == '<') depth++;
            else if (c == '>') depth--;
        }
    }

    /**
     * Extracts the simple type-parameter names of a class Signature like
     * {@code <T:Ljava/lang/Object;>Ljava/lang/Object;}, or "" when none/unparsable.
     */
    static String typeParams(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '<') return "";
        try {
            P p = new P(s);
            p.expect('<');
            List<String> names = new ArrayList<>();
            for (;;) {
                if (p.peek() == '>') break;
                StringBuilder id = new StringBuilder();
                while (!p.end() && p.peek() != ':') { id.append((char) p.peek()); p.i++; }
                if (id.length() == 0) throw new RuntimeException();
                names.add(id.toString());
                if (p.peek() == ':') {
                    p.i++;
                    if (p.peek() == ':') p.i++;
                    p.fieldType(); // class bound (required per spec)
                }
                while (p.peek() == ':') { // interface bounds
                    p.i++;
                    if (p.peek() == '>') throw new RuntimeException();
                    p.fieldType();
                }
            }
            p.expect('>');
            if (p.end()) throw new RuntimeException();
            return names.isEmpty() ? "" : "<" + String.join(", ", names) + ">";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Number of leading type parameters declared in a Signature like {@code <T:Ljava/lang/Object;>...}. */
    static int typeParamCount(String s) {
        String tp = typeParams(s);
        if (tp.length() < 2) return 0;
        return tp.substring(1, tp.length() - 1).split(",").length;
    }

    /** Signature cursor. */
    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        boolean end() { return i >= s.length(); }

        int peek() { return i < s.length() ? s.charAt(i) : -1; }

        boolean ok() { return i >= s.length(); }

        void expect(char c) {
            if (peek() != c) throw new RuntimeException();
            i++;
        }

        String fieldType() {
            return switch (peek()) {
                case 'L' -> classType();
                case '[' -> {
                    i++;
                    yield fieldType() + "[]";
                }
                case 'T' -> { // type variable
                    i++;
                    int j = s.indexOf(';', i);
                    if (j < 0) throw new RuntimeException();
                    String n = s.substring(i, j);
                    i = j + 1;
                    yield n;
                }
                case 'B' -> { i++; yield "byte"; }
                case 'C' -> { i++; yield "char"; }
                case 'D' -> { i++; yield "double"; }
                case 'F' -> { i++; yield "float"; }
                case 'I' -> { i++; yield "int"; }
                case 'J' -> { i++; yield "long"; }
                case 'S' -> { i++; yield "short"; }
                case 'Z' -> { i++; yield "boolean"; }
                case 'V' -> { i++; yield "void"; }
                default -> throw new RuntimeException();
            };
        }

        String classType() {
            expect('L');
            StringBuilder sb = new StringBuilder();
            for (;;) {
                int j = i;
                while (j < s.length() && s.charAt(j) != ';' && s.charAt(j) != '<' && s.charAt(j) != '.') j++;
                if (j == i) throw new RuntimeException();
                sb.append(s.substring(i, j).replace('$', '.').replace('/', '.'));
                i = j;
                if (peek() == '<') sb.append(typeArgs());
                if (peek() == '.') {
                    i++;
                    sb.append('.');
                    continue;
                }
                if (peek() == ';') { i++; return sb.toString(); }
                throw new RuntimeException();
            }
        }

        String typeArgs() {
            expect('<');
            StringBuilder sb = new StringBuilder("<");
            boolean first = true;
            while (peek() != '>') {
                if (!first) sb.append(", ");
                first = false;
                if (peek() == '*') { i++; sb.append("?"); }
                else if (peek() == '+') { i++; sb.append("? extends ").append(fieldType()); }
                else if (peek() == '-') { i++; sb.append("? super ").append(fieldType()); }
                else sb.append(fieldType());
            }
            expect('>');
            return sb.append('>').toString();
        }
    }
}