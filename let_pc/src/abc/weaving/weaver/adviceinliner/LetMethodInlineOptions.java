
package abc.weaving.weaver.adviceinliner;

public class LetMethodInlineOptions extends IfMethodInlineOptions
{
    public boolean considerForInlining(String name) {
        return name.startsWith("let$");
    }	
}

