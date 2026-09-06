import java.util.*;
import java.util.function.*;
import java.util.concurrent.Callable;

public final class DecompileStress {

    private static final Map<String, Integer> CACHE = new HashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println(run("ysak", 7));
        System.out.println(obfuscatedFlow(args.length));
        System.out.println(genericStuff(List.of("a", "bb", "ccc")));
    }

    public static String run(String input, int n) throws Exception {
        Callable<String> task = () -> {
            String value = input.repeat(Math.max(1, n % 4 + 1));

            try {
                return transform(value);
            } finally {
                CACHE.merge(input, n, Integer::sum);
            }
        };

        return task.call();
    }

    private static String transform(String value) {
        Function<String, String> upper = String::toUpperCase;

        return Arrays.stream(value.split(""))
                .map(upper)
                .filter(s -> !s.isBlank())
                .reduce("", (a, b) -> a + b);
    }

    public static int obfuscatedFlow(int x) {
        int result = 0;
        int state = x & 7;

        while (true) {
            switch (state) {
                case 0:
                    result += 17;
                    state = (x ^ result) & 3;
                    break;

                case 1:
                    result ^= 0x55;
                    state = (result + x) & 7;
                    break;

                case 2:
                    result *= 3;
                    if ((result & 1) == 0) {
                        state = 4;
                    } else {
                        state = 6;
                    }
                    break;

                case 3:
                    result -= x;
                    state = Math.abs(result) & 7;
                    break;

                case 4:
                    result = Integer.rotateLeft(result, 5);
                    state = 7;
                    break;

                case 5:
                    result += x * 13;
                    state = 1;
                    break;

                case 6:
                    result = Integer.reverse(result);
                    state = 3;
                    break;

                default:
                    return result;
            }
        }
    }

    public static <T> String genericStuff(List<T> values) {
        List<T> copy = new ArrayList<>(values);

        copy.sort((a, b) -> {
            int x = Objects.hashCode(a);
            int y = Objects.hashCode(b);

            return Integer.compare(
                    Integer.bitCount(x),
                    Integer.bitCount(y)
            );
        });

        return copy.stream()
                .map(Object::toString)
                .reduce(
                        new StringBuilder(),
                        (builder, value) -> builder.append(value).append(':'),
                        StringBuilder::append
                )
                .toString();
    }

    private interface Processor<T> {
        T process(T value);
    }

    private static final class Nested<T> implements Processor<T> {
        private final T value;

        private Nested(T value) {
            this.value = value;
        }

        @Override
        public T process(T ignored) {
            return value;
        }
    }

    public static Object anonymousExample(Object input) {
        Processor<Object> processor = new Processor<>() {
            @Override
            public Object process(Object value) {
                if (value == null) {
                    return "null";
                }

                return String.valueOf(value)
                        .replace('\0', '_')
                        .trim();
            }
        };

        return processor.process(input);
    }
}
