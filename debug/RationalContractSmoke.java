import android.util.Rational;
import java.math.BigInteger;
import java.util.Random;

/** Independent checks of the documented public Rational value contract. */
public final class RationalContractSmoke {
    private interface Case { void run(); }
    private static int failures;
    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("contract mismatch");
    }
    private static void check(String name, Case c) {
        try {
            c.run();
            System.out.println("PASS " + name);
        } catch (Throwable e) {
            failures++;
            System.out.println("FAIL " + name + ": " + e.getClass().getSimpleName());
        }
    }
    private static void expect(Class<? extends Throwable> type, Case c) {
        try { c.run(); } catch (Throwable t) {
            require(type.isInstance(t));
            return;
        }
        throw new AssertionError("missing exception " + type.getSimpleName());
    }
    private static int exactCompare(Rational a, Rational b) {
        BigInteger l=BigInteger.valueOf(a.getNumerator()).multiply(BigInteger.valueOf(b.getDenominator()));
        BigInteger r=BigInteger.valueOf(b.getNumerator()).multiply(BigInteger.valueOf(a.getDenominator()));
        int sign = l.compareTo(r);
        return (a.getDenominator() < 0) != (b.getDenominator() < 0) ? -sign : sign;
    }
    public static void main(String[] args) {
        check("finite reduction and signs", () -> {
            require(new Rational(2,4).equals(new Rational(1,2)));
            require(new Rational(-3,-6).equals(new Rational(1,2)));
            require(new Rational(3,-6).equals(new Rational(-1,2)));
            require(new Rational(1,2).hashCode()==new Rational(2,4).hashCode());
            require(new Rational(0,9).equals(Rational.ZERO));
        });
        check("minimum numerator reduction stays canonical", () -> {
            Rational a = new Rational(Integer.MIN_VALUE,Integer.MAX_VALUE);
            require(a.getNumerator()==Integer.MIN_VALUE && a.getDenominator()==Integer.MAX_VALUE);
            require(a.intValue()==-1 && a.longValue()==-1 && a.compareTo(new Rational(-1,1))<0);
            Rational b = new Rational(Integer.MIN_VALUE,Integer.MAX_VALUE-1);
            require(b.getNumerator()==-1073741824 && b.getDenominator()==1073741823);
            require(b.equals(new Rational(-1073741824,1073741823)));
        });
        check("finite integral conversions truncate", () -> {
            require(new Rational(7,3).intValue()==2);
            require(new Rational(-7,3).intValue()==-2);
            require(new Rational(-7,3).longValue()==-2);
            require(new Rational(Integer.MIN_VALUE,1).longValue()==Integer.MIN_VALUE);
        });
        check("positive infinity int saturates", () -> require(Rational.POSITIVE_INFINITY.intValue()==Integer.MAX_VALUE));
        check("negative infinity int saturates", () -> require(Rational.NEGATIVE_INFINITY.intValue()==Integer.MIN_VALUE));
        check("positive infinity long saturates", () -> require(Rational.POSITIVE_INFINITY.longValue()==Long.MAX_VALUE));
        check("negative infinity long saturates", () -> require(Rational.NEGATIVE_INFINITY.longValue()==Long.MIN_VALUE));
        check("NaN integral conversions return zero", () -> require(Rational.NaN.intValue()==0 && Rational.NaN.longValue()==0));
        check("narrow conversions follow int", () -> {
            require(new Rational(257,1).byteValue()==(byte)257);
            require(Rational.POSITIVE_INFINITY.shortValue()==(short)Integer.MAX_VALUE);
            require(Rational.NEGATIVE_INFINITY.byteValue()==(byte)Integer.MIN_VALUE);
        });
        check("non-finite floating values", () -> {
            require(Double.isNaN(Rational.NaN.doubleValue()) && Float.isNaN(Rational.NaN.floatValue()));
            require(Rational.POSITIVE_INFINITY.doubleValue()==Double.POSITIVE_INFINITY);
            require(Rational.NEGATIVE_INFINITY.floatValue()==Float.NEGATIVE_INFINITY);
        });
        check("float converts both operands before division", () -> {
            Rational n=new Rational(16777217,16777219);
            float expected=(float)n.getNumerator()/(float)n.getDenominator();
            require(Float.floatToIntBits(n.floatValue())==Float.floatToIntBits(expected));
        });
        check("double finite conversion", () -> {
            Rational n=new Rational(-1234567,2345678);
            require(n.doubleValue()==(double)n.getNumerator()/(double)n.getDenominator());
        });
        check("nearby positive fractions compare exactly", () -> {
            Rational a=new Rational(1000000000,1000000001), b=new Rational(999999999,1000000000);
            require(!a.equals(b));
            require(a.compareTo(b)>0 && b.compareTo(a)<0);
        });
        check("nearby negative fractions compare exactly", () -> {
            Rational a=new Rational(-1000000000,1000000001), b=new Rational(-999999999,1000000000);
            require(a.compareTo(b)<0 && b.compareTo(a)>0);
        });
        check("special values natural order", () -> {
            Rational[] ordered={Rational.NEGATIVE_INFINITY,new Rational(-1,2),Rational.ZERO,
                    new Rational(1,2),Rational.POSITIVE_INFINITY,Rational.NaN};
            for (int i=0;i<ordered.length;i++) for (int j=0;j<ordered.length;j++) {
                require(Integer.signum(ordered[i].compareTo(ordered[j]))==Integer.compare(i,j));
            }
        });
        check("compareTo rejects null including NaN", () -> {
            expect(NullPointerException.class, () -> new Rational(1,2).compareTo(null));
            expect(NullPointerException.class, () -> Rational.NaN.compareTo(null));
        });
        check("slash parse and normalization", () -> {
            require(Rational.parseRational("-3/-6").equals(new Rational(1,2)));
            require(Rational.parseRational("+7/3").equals(new Rational(7,3)));
        });
        check("colon parse and normalization", () -> {
            require(Rational.parseRational("3:+6").equals(new Rational(1,2)));
            require(Rational.parseRational("-3:-6").equals(new Rational(1,2)));
            require(Rational.parseRational("0:9").equals(Rational.ZERO));
        });
        check("parse special values and round trips", () -> {
            for (Rational r : new Rational[]{Rational.NaN,Rational.POSITIVE_INFINITY,Rational.NEGATIVE_INFINITY,
                    Rational.ZERO,new Rational(-4,9),new Rational(123,456)}) {
                require(Rational.parseRational(r.toString()).equals(r));
            }
        });
        check("parse rejects null with NullPointerException", () -> expect(NullPointerException.class, () -> Rational.parseRational(null)));
        check("parse rejects malformed strings", () -> {
            for (String s : new String[]{"","4.56","3:4/5","1/","/2"," 1/2","2147483648/1"})
                expect(NumberFormatException.class, () -> Rational.parseRational(s));
        });
        check("1000 finite comparisons against independent integer oracle", () -> {
            Random random=new Random(92417);
            for (int i=0;i<1000;i++) {
                Rational a,b;
                if (i%2==0) {
                    int n=500000000+random.nextInt(1000000000);
                    int sign=random.nextBoolean()?1:-1;
                    a=new Rational(sign*n,n+1); b=new Rational(sign*(n-1),n);
                } else {
                    a=new Rational(random.nextInt(),1+random.nextInt(Integer.MAX_VALUE));
                    b=new Rational(random.nextInt(),1+random.nextInt(Integer.MAX_VALUE));
                }
                require(Integer.signum(a.compareTo(b))==Integer.signum(exactCompare(a,b)));
                require(Integer.signum(b.compareTo(a))==-Integer.signum(exactCompare(a,b)));
            }
        });
        if (failures != 0) {
            System.out.println("RESULT FAIL " + failures);
            System.exit(1);
        }
        System.out.println("RESULT PASS");
    }
}
