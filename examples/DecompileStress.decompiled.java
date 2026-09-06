public final class DecompileStress {
  private static final java.util.Map<java.lang.String, java.lang.Integer> CACHE;

  public DecompileStress() {
    super();
    return;
  }

  public static void main(String[] arg0) throws Exception {
    System.out.println(run("ysak", 7));
    System.out.println(obfuscatedFlow(arg0.length));
    System.out.println(genericStuff(java.util.List.of("a", "bb", "ccc")));
    return;
  }

  public static String run(String arg0, int arg1) throws Exception {
    // lambda lambda$run$0: body has exception handlers; try/finally not reconstructed (safe null)
    java.util.concurrent.Callable v0 = null;
    return (String) v0.call();
  }

  private static String transform(String arg0) {
    java.util.function.Function<String, String> v0 = String::toUpperCase;
    return (String) java.util.Arrays.stream(arg0.split("")).map(v0).filter((String p0) -> { boolean $t0 = false; if (p0.isBlank()) {   $t0 = false; } else {   $t0 = true; } return $t0; }).reduce("", (String p0, String p1) -> ""+p0+p1);
  }

  public static int obfuscatedFlow(int arg0) {
    int v0 = 0;
    int v1 = arg0&7;
    while (true) {
      switch (v1) {
      case 0:
          v0 += 17;
          v1 = (arg0^v0)&3;
          continue;
      case 1:
          v0 = v0^85;
          v1 = v0+arg0&7;
          continue;
      case 2:
          v0 = v0*3;
          if ((v0&1) != 0) {
            v1 = 6;
            continue;
          }
          else {
            v1 = 4;
            continue;
          }
      case 3:
          v0 = v0-arg0;
          v1 = Math.abs(v0)&7;
          continue;
      case 4:
          v0 = Integer.rotateLeft(v0, 5);
          v1 = 7;
          continue;
      case 5:
          v0 = v0+arg0*13;
          v1 = 1;
          continue;
      case 6:
          v0 = Integer.reverse(v0);
          v1 = 3;
          continue;
      default:
          return v0;
      }
    }
  }

  public static <T> java.lang.String genericStuff(java.util.List<T> arg0) {
    java.util.ArrayList<T> v0 = new java.util.ArrayList<>(arg0);
    v0.sort((Object p0, Object p1) -> { int $v0 = java.util.Objects.hashCode(p0); int $v1 = java.util.Objects.hashCode(p1); return Integer.compare(Integer.bitCount($v0), Integer.bitCount($v1)); });
    return ((StringBuilder) v0.stream().map(Object::toString).reduce(new StringBuilder(), (StringBuilder p0, String p1) -> p0.append(p1).append(':'), StringBuilder::append)).toString();
  }

  public static Object anonymousExample(Object arg0) {
    Processor<java.lang.Object> v0 = new Processor<java.lang.Object>() {
      public Object process(Object arg0) {
        if (arg0 != null) {
          return String.valueOf(arg0).replace('\0', '_').trim();
        }
        else {
          return "null";
        }
      }
    };
    return v0.process(arg0);
  }

  static {
    CACHE = new java.util.HashMap();
  }

  final class Nested<T> implements Processor<T> {
    private final T value;

    private Nested(T arg0) {
      super();
      value = arg0;
      return;
    }

    public T process(T arg0) {
      return value;
    }
  }

  interface Processor<T> {

    public abstract T process(T arg0);
  }
}

