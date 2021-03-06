aspect FibExtAspect {
    
    public static int callCtr = 0;
    public static int getCtr = 0;
    public static int setCtr = 0;
    
    pointcut fib(int x): call(* fib(int)) && args(x);
    pointcut getPrevX() : get(int A.prevX);
    pointcut setPrevX() : set(int A.prevX);
    
    before(int x) : fib(x) {
        System.out.println("In FibExtAspect: Before fib(" + x + ")");
        callCtr++;
    }
    
    before() : getPrevX() {
        System.out.println("In FibExtAspect: Before getPrevX");
        getCtr++;
    }
    
    before() : setPrevX() {
        System.out.println("In FibExtAspect: Before setPrevX");
        setCtr++;
    }
}