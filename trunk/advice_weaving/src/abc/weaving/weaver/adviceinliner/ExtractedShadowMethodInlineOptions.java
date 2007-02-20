
package abc.weaving.weaver.adviceinliner;

import polyglot.util.InternalCompilerError;
import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;

public class ExtractedShadowMethodInlineOptions implements InlineOptions {
    public boolean considerForInlining(String name) {
        return name.startsWith("shadow$");
    }
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();

        //debug("PROCEED: " + method);
        if (!considerForInlining(expr.getMethodRef().name()))
            return InlineOptions.DONT_INLINE;

        if (!method.isStatic())
            throw new InternalCompilerError("");


        if (!method.getDeclaringClass().equals(container.getDeclaringClass())) {
            int accessViolations=AdviceInliner.getAccessViolationCount(container, method);
            if (accessViolations>0)
                return DONT_INLINE;
        }  

        AdviceInliner.debug("Trying to inline shadow method " + method);

        //			 we now *always* inline proceed 
        // because the shadow is always tiny due to the extraction.

        if (AdviceInliner.v().aroundForceInline()) {
            AdviceInliner.debug("force inline on.");
            return InlineOptions.INLINE_DIRECTLY;
        }

        int size=method.getActiveBody().getUnits().size()
            - method.getParameterCount();
        AdviceInliner.debug("  size: " + size);
        if (size<3)
            return INLINE_DIRECTLY;

        return DONT_INLINE;
    }
}
