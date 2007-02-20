public class BoolVariable {
    public static void f(boolean o) { }
    public static void main(String[] args) {
	f(true);
    }
}

aspect FG {
    tracematch(boolean o) {
	sym f after : call(* *.f(..)) && args(o);

	f

	    {
		System.out.println(o);
	    }
    }
}
