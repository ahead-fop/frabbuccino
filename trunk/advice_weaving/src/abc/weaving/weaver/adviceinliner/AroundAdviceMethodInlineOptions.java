
package abc.weaving.weaver.adviceinliner;

import soot.SootMethod;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import abc.soot.util.AroundShadowInfoTag;
import abc.weaving.weaver.around.AroundWeaver;
import abc.weaving.weaver.around.Util;

public class AroundAdviceMethodInlineOptions implements InlineOptions {
    public boolean considerForInlining(String name) {
        return Util.isAroundAdviceMethodName(name);
    }
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();
        if (!Util.isAroundAdviceMethodName(expr.getMethodRef().name()))
            return InlineOptions.DONT_INLINE;

        int bDidInline=internalInline(container, stmt, expr);
        if (bDidInline!=InlineOptions.INLINE_DIRECTLY) {
            //adviceMethodsNotInlined.add(method);
        }
        return bDidInline;
    }
    private int internalInline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();

        //debug("   Trying to inline advice method " + method);

        if (AdviceInliner.v().aroundForceInline()) {
            //	debug("    force inline on.");
            return InlineOptions.INLINE_DIRECTLY;	
        } else if (true) {				
            /*if (container.getName().startsWith("inline$")) {
            //if (true)throw new InternalCompilerError("");
            return DONT_INLINE;
            } else if (Util.isAroundAdviceMethodName(container.getName())) {
            return DONT_INLINE;
            } else {
            //if (true)throw new InternalCompilerError("");

*/	
            if (method==container)
                return InlineOptions.DONT_INLINE;
            /// dirty hack!
            if (container.getName().startsWith("inline$") && container.getName().endsWith(method.getName()))
                return InlineOptions.DONT_INLINE;

            AdviceInliner.debug("    container: " + container.getName());
            return InlineOptions.INLINE_STATIC_METHOD;
            //}
        }
        // unreachable code below.

        AroundWeaver.AdviceMethodInlineInfo info=
            AroundWeaver.v().getAdviceMethodInlineInfo(method);

        AroundWeaver.ShadowInlineInfo shadowInfo=null;
        AdviceInliner.debug("Proceed method: " + method);

        if (stmt.hasTag("AroundShadowInfoTag"))	{
            AroundShadowInfoTag tag=
                (AroundShadowInfoTag)stmt.getTag("AroundShadowInfoTag");

            AdviceInliner.debug(" Found tag.");
            shadowInfo=tag.shadowInfo;
        }
        if (shadowInfo!=null) {
            if (shadowInfo.weavingRequiredUnBoxing) {
                AdviceInliner.debug(" (Un-)Boxing detected. Inlining.");
                return InlineOptions.INLINE_STATIC_METHOD;
            }
        }

        int accessViolations=AdviceInliner.getAccessViolationCount(container, method);
        if (accessViolations!=0) {
            AdviceInliner.debug("Access violations");
            AdviceInliner.debug(" Method: " + container);
            AdviceInliner.debug(" Advice method: " + method); 
            AdviceInliner.debug(" Violations: " + accessViolations);
            if (accessViolations>1)
                return InlineOptions.DONT_INLINE;					
        }

        if (info.nestedClasses) {
            AdviceInliner.debug(" Skipped (nested classes)");
            return InlineOptions.DONT_INLINE;
        }

        //if (info.proceedInvocations>1)
        AdviceInliner.debug(" Size of advice method: " + info.originalSize);
        AdviceInliner.debug(" Number of applications: " + info.applications);
        AdviceInliner.debug(" Number of added locals (approximately): " + info.internalLocalCount);
        AdviceInliner.debug(" Proceed invocations: " + info.proceedInvocations);


        //if (info.originalSize< (20 >> (depth-1)))
        //	return InlineOptions.INLINE_STATIC_METHOD;

        //if (info.internalLocalCount==0)
        //	return true;
        //if (info.applications==1)
        //	return true;

        return InlineOptions.DONT_INLINE;
    }
}
