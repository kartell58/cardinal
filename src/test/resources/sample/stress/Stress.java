package stress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.Function;

public class Stress {

    interface Op<T> {
        T apply(T a, T b);
    }

    static final class Pair<T> implements Op<T> {
        private final T left;

        Pair(T left) {
            this.left = left;
        }

        public T apply(T a, T b) {
            return b == null ? left : a;
        }
    }

    public static String transform(String value) {
        Function<String, String> upper = String::toUpperCase;
        return java.util.Arrays.stream(value.split("")).map(upper)
                .filter((String s) -> !s.isBlank())
                .reduce("", (a, b) -> a + b);
    }

    public static String join(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        int off = "x".length();
        copy.sort((String a, String b) -> {
            int x = a.length() + off;
            int y = b.length() + off;
            return Integer.compare(x, y);
        });
        BinaryOperator<StringBuilder> comb = StringBuilder::append;
        return copy.stream().map(String::toLowerCase)
                .reduce(new StringBuilder(), (sb, s) -> sb.append(s).append(':'), comb).toString();
    }

    public static void main(String[] arg0) throws Exception {
        System.out.println(transform("ysak 7"));
        System.out.println(join(java.util.List.of("a", "bb", "ccc")));
        Op<String> f = new Op<String>() {
            public String apply(String a, String b) {
                return a + b;
            }
        };
        System.out.println(f.apply("ab", "cd"));
    }
}