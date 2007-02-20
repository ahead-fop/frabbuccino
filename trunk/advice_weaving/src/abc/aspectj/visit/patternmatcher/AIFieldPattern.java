
package abc.aspectj.visit.patternmatcher;

import soot.Modifier;
import soot.Scene;
import soot.SootMethod;
import soot.SootField;
import soot.SootFieldRef;
import soot.SootClass;

import abc.weaving.aspectinfo.MethodCategory;

class AIFieldPattern implements abc.weaving.aspectinfo.FieldPattern {
    private abc.aspectj.ast.FieldPattern pattern;

    public AIFieldPattern(abc.aspectj.ast.FieldPattern pattern) {
        this.pattern = pattern;
    }

    public abc.aspectj.ast.FieldPattern getPattern() {
        return pattern;
    }

    public boolean matchesFieldRef(SootFieldRef sfr) {
        int mods = MethodCategory.getModifiers(sfr);
        String name = MethodCategory.getName(sfr);
        SootClass realcl = MethodCategory.getClass(sfr);
        SootFieldRef realfr = Scene.v().makeFieldRef(realcl, name, sfr.type(), Modifier.isStatic(mods));
        boolean matches =
            PatternMatcher.v().matchesType(pattern.getType(), sfr.type().toString()) &&
            pattern.getName().name().getPattern().matcher(name).matches() &&
            PatternMatcher.v().matchesModifiers(pattern.getModifiers(), mods) &&
            (PatternMatcher.v().matchesClass(pattern.getName().base(), realcl) ||
             (PatternMatcher.v().containsField(realcl, name, sfr.type(), Modifier.isStatic(mods)) &&
              PatternMatcher.v().matchesClassSubclassOf(pattern.getName().base(), realcl, realfr.resolve().getDeclaringClass())));
        if (abc.main.Debug.v().patternMatches) {
            System.err.println("Matching field pattern "+pattern+" against "+sfr+": "+matches);
        }
        return matches;
    }

    public boolean matchesMethod(SootMethod sm) {
        int cat = MethodCategory.getCategory(sm);
        if (!(cat == MethodCategory.ACCESSOR_GET || cat == MethodCategory.ACCESSOR_SET)) {
            return false;
        }
        String name = MethodCategory.getName(sm);
        SootClass realcl = MethodCategory.getClass(sm);
        //FIXME: This will not work for inner classes
        SootField sf = realcl.getField(name);
        return matchesFieldRef(sf.makeRef());
    }

    public String toString() {
        return pattern.toString();
    }

    public boolean equivalent(abc.weaving.aspectinfo.FieldPattern otherpat) {
        return pattern.equivalent(otherpat.getPattern());
    }

}
