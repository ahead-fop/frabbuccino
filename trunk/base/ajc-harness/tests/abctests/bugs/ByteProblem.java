public class ByteProblem {

    public final static byte TheConstant = 3;

    public void foo(byte x) {}

    public void reffoo(){
        foo(TheConstant);
    }
}

