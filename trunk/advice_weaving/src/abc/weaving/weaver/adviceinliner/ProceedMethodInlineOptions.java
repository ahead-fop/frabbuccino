
package abc.weaving.weaver.adviceinliner;

import soot.SootMethod;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.toolkits.scalar.Evaluator;
import abc.main.options.OptionsParser;
import abc.soot.util.AroundShadowInfoTag;
import abc.weaving.weaver.around.AroundWeaver;
import abc.weaving.weaver.around.Util;

public class ProceedMethodInlineOptions implements InlineOptions {
    public ProceedMethodInlineOptions() {

    }
    public boolean considerForInlining(String methodName) {
        return Util.isProceedMethodName(methodName);
    }
    public int inline(SootMethod container, Stmt stmt, InvokeExpr expr) {
        SootMethod method=expr.getMethod();

        //debug("PROCEED: " + method);
        if (!considerForInlining(expr.getMethodRef().name()))
            return InlineOptions.DONT_INLINE;

        if (!method.isStatic())
            return InlineOptions.DONT_INLINE;

        if (!method.getDeclaringClass().equals(container.getDeclaringClass())) {
            if (OptionsParser.v().around_force_inlining())
                return InlineOptions.DONT_INLINE;
            else { 
                if (container.getName().startsWith("inline$")) /// is there a better way to express this?
                    return InlineOptions.INLINE_DIRECTLY;
                else
                    return DONT_INLINE;
            }
        }

        AdviceInliner.debug("Trying to inline proceed method " + method);

        //			 we now *always* inline proceed 
        // because the shadow is always tiny due to the extraction.

        if (true)
            return InlineOptions.INLINE_DIRECTLY;
        // unreachable code below

        if (AdviceInliner.v().aroundForceInline()) {
            AdviceInliner.debug("force inline on.");
            return InlineOptions.INLINE_DIRECTLY;
        }


        AroundWeaver.ProceedMethodInlineInfo info=					
            AroundWeaver.v().getProceedMethodInlineInfo(method);

        AroundWeaver.ShadowInlineInfo shadowInfo=null;
        AdviceInliner.debug("Proceed method: " + method);

        if (stmt.hasTag("AroundShadowInfoTag"))	{
            AroundShadowInfoTag tag=
                (AroundShadowInfoTag)stmt.getTag("AroundShadowInfoTag");

            AdviceInliner.debug(" Found tag.");
            shadowInfo=tag.shadowInfo;
        } else {
            soot.Value v=expr.getArg(info.shadowIDParamIndex);
            if (Evaluator.isValueConstantValued(v)) {
                v = Evaluator.getConstantValueOf(v);
                int shadowID=((IntConstant) v).value;

                shadowInfo=
                    (AroundWeaver.ShadowInlineInfo) info.shadowInformation.get(new Integer(shadowID));                 	

                stmt.addTag(new AroundShadowInfoTag(
                            shadowInfo));
            }
        }
        if (shadowInfo!=null) {
            AdviceInliner.debug(" Shadow size: " + shadowInfo.size);
            AdviceInliner.debug(" Number of additional locals (approximately): " + shadowInfo.internalLocals);
        } else {
            AdviceInliner.debug(" Could not find shadow information.");				
        }
        if (shadowInfo!=null) {
            if (shadowInfo.weavingRequiredUnBoxing) {
                AdviceInliner.debug(" (Un-)Boxing detected. Inlining.");
                return INLINE_DIRECTLY;
            }

            if (shadowInfo.size<10)
                return INLINE_DIRECTLY;

            //if (shadowInfo.internalLocals==0)
            //	return true;
        }




        return DONT_INLINE;
    }
}
