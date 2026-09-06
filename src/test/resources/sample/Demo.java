package sample;

public class Demo {
    private int count;
    private static final int MAX = 10;

    static {
        System.out.println("init");
    }

    public Demo() {
        this(0);
    }

    public Demo(int n) {
        this.count = n;
    }

    public int add(int x) {
        return count + x;
    }

    public int max(int a, int b) {
        return a > b ? a : b;
    }

    public int sumTo(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += i;
        return s;
    }

    public int describe(long v) {
        if (v > 100) return 1;
        if (v < 0) return -1;
        return 0;
    }

    public String color(int c) {
        switch (c) {
            case 1:
                return "red";
            case 2:
            case 3:
                return "green";
            default:
                return "?";
        }
    }

    public int doWhile(int n) {
        int i = 0;
        do {
            i += 2;
        } while (i < n);
        return i;
    }

    public boolean flag(boolean a, boolean b) {
        return a && !b;
    }
}