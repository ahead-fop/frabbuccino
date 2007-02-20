
package abc.weaving.weaver.adviceinliner;

import soot.Body;
import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class AfterBeforeMethodInlineOptions implements InlineOptions {
    public boolean considerForInlining(String name) {
        return AdviceInliner.isAfterBeforeAdviceMethod(name);
    }

    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();
        if (!considerForInlining(expr.getMethodRef().name()))
            return InlineOptions.DONT_INLINE;

        AdviceInliner.debug("    Trying to inline advice method " + method);

        if (AdviceInliner.v().afterBeforeForceInline()) {
            AdviceInliner.debug("    force inline on.");
            return InlineOptions.INLINE_DIRECTLY;	
        } 

        int accessViolations=AdviceInliner.getAccessViolationCount(container, method);
        if (accessViolations>0) {
            AdviceInliner.debug("Access violations");
            AdviceInliner.debug(" Method: " + container);
            AdviceInliner.debug(" Advice method: " + method); 
            return InlineOptions.DONT_INLINE;
        }
        Body body=method.getActiveBody();

        //if (info.proceedInvocations>1)
        int size=body.getUnits().size()-method.getParameterCount();
        AdviceInliner.debug("     Size of advice method: " + size);
        int addedLocals=body.getLocalCount()-method.getParameterCount();
        AdviceInliner.debug("     Number of added locals (approximately): " + addedLocals);			

        if (size<6)
            return InlineOptions.INLINE_DIRECTLY;


        return InlineOptions.DONT_INLINE;
    }
}

