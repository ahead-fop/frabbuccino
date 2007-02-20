
package abc.aspectj.visit.patternmatcher;

import java.util.LinkedList;
import soot.SootMethod;
import soot.SootClass;
import abc.weaving.aspectinfo.MethodCategory;

class AIConstructorPattern implements abc.weaving.aspectinfo.ConstructorPattern {
    private abc.aspectj.ast.ConstructorPattern pattern;

    public AIConstructorPattern(abc.aspectj.ast.ConstructorPattern pattern) {
        this.pattern = pattern;
    }

    public abc.aspectj.ast.ConstructorPattern getPattern() {
        return pattern;
    }

    public boolean matchesConstructor(SootMethod method) {
        int mods = MethodCategory.getModifiers(method);
        SootClass realcl = MethodCategory.getClass(method);
        LinkedList/*<soot.Type>*/ ftypes = new LinkedList(method.getParameterTypes());
        int skip_first = MethodCategory.getSkipFirst(method);
        int skip_last = MethodCategory.getSkipLast(method);
        //System.out.println("Real name: "+name+" "+skip_first+" "+skip_last);
        while (skip_first-- > 0) ftypes.removeFirst();
        while (skip_last-- > 0) ftypes.removeLast();
        boolean matches =
            PatternMatcher.v().matchesModifiers(pattern.getModifiers(), mods) &&
            PatternMatcher.v().matchesClass(pattern.getName().base(), realcl) &&
            PatternMatcher.v().matchesFormals(pattern.getFormals(), ftypes) &&
            PatternMatcher.v().matchesThrows(pattern.getThrowspats(), method.getExceptions());
        if (abc.main.Debug.v().patternMatches) {
            System.err.println("Matching constructor pattern "+pattern+" against "+method+": "+matches);
        }
        return matches;
    }

    public String toString() {
        return pattern.toString();
    }

    public boolean equivalent(abc.weaving.aspectinfo.ConstructorPattern otherpat) {
        return pattern.equivalent(otherpat.getPattern());
    }

}
