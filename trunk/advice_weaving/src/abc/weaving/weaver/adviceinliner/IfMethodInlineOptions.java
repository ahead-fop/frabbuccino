
package abc.weaving.weaver.adviceinliner;

import soot.Body;
import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class IfMethodInlineOptions implements InlineOptions {
    public boolean considerForInlining(String name) {
        return name.startsWith("if$");
    }
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();

        if (!considerForInlining(expr.getMethodRef().name()))
            return DONT_INLINE;

        if (!method.isStatic())
            return DONT_INLINE;

        //if (!method.getDeclaringClass().equals(container.getDeclaringClass()))
        //	return false;

        AdviceInliner.debug("Trying to inline method " + method);

        if (AdviceInliner.v().aroundForceInline() || AdviceInliner.v().afterBeforeForceInline()) { /// hack
            AdviceInliner.debug("force inline on.");
            return INLINE_DIRECTLY;
        }

        int accessViolations=AdviceInliner.getAccessViolationCount(container, method);
        if (accessViolations!=0) {
            AdviceInliner.debug("Access violations");
            AdviceInliner.debug(" Method: " + container);
            AdviceInliner.debug(" Advice method: " + method); 
            AdviceInliner.debug(" Violations: " + accessViolations);
            if (accessViolations>0)
                return DONT_INLINE;					
        }

        Body body=method.getActiveBody();

        //if (info.proceedInvocations>1)
        int size=body.getUnits().size();
        AdviceInliner.debug(" Size of method: " + size);
        int addedLocals=body.getLocalCount()-method.getParameterCount();
        AdviceInliner.debug(" Number of added locals (approximately): " + addedLocals);			

        if (size<6)
            return INLINE_DIRECTLY;


        return DONT_INLINE;
    }
}

