
package abc.weaving.weaver.adviceinliner;

import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public interface InlineOptions {
    public final static int DONT_INLINE=0;
    public final static int INLINE_STATIC_METHOD=1;
    public final static int INLINE_DIRECTLY=2;
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr);
    public boolean considerForInlining(String methodName);
}

