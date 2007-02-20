
package abc.weaving.weaver.adviceinliner;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class CombinedInlineOptions implements InlineOptions {
    public List inlineOptions=new ArrayList();
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();
        String name=expr.getMethodRef().name();

        for (Iterator it=inlineOptions.iterator(); it.hasNext();) {
            InlineOptions opts=(InlineOptions)it.next();
            if (opts.considerForInlining(name)) {
                return opts.inline(container, stmt, expr);
            }
        }
        return DONT_INLINE;
    }
    public boolean considerForInlining(String methodName) {
        for (Iterator it=inlineOptions.iterator(); it.hasNext();) {
            InlineOptions opts=(InlineOptions)it.next();
            if (opts.considerForInlining(methodName)) {
                return true;
            }
        }
        return false;
    }
}

