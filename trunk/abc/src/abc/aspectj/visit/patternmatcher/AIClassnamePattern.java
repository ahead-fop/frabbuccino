
package abc.aspectj.visit.patternmatcher;

import soot.SootClass;
import abc.aspectj.ast.ClassnamePatternExpr;
import abc.weaving.aspectinfo.ClassnamePattern;

class AIClassnamePattern implements ClassnamePattern {
    private ClassnamePatternExpr pattern;

    public AIClassnamePattern(ClassnamePatternExpr pattern) {
        this.pattern = pattern;
    }

    public ClassnamePatternExpr getPattern() {
        return pattern;
    }

    public boolean matchesClass(SootClass sc) {
        boolean matches = PatternMatcher.v().matchesClass(pattern, sc);
        if (abc.main.Debug.v().patternMatches) {
            System.err.println("Matching classname pattern "+pattern+" against "+sc+": "+matches);
        }
        return matches;
    }

    public String toString() {
        return pattern.toString();
    }

    public boolean equivalent(ClassnamePattern otherpat) {
        return pattern.equivalent(otherpat.getPattern());
    }
}

