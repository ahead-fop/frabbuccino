/* abc - The AspectBench Compiler
 * Copyright (C) 2004 Oege de Moor
 * Copyright (C) 2004 Aske Simon Christensen
 *
 * This compiler is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This compiler is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this compiler, in the file LESSER-GPL;
 * if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */

package abc.aspectj.ast;

import abc.aspectj.visit.*;
import abc.aspectj.visit.patternmatcher.PatternMatcher;

import polyglot.ast.*;

import polyglot.types.*;
import polyglot.util.*;
import polyglot.visit.*;
import java.util.*;

/** a (class+) ClassnamePatternExpr that matches all subclasses.
 * 
 *  @author Oege de Moor
 *  @author Aske Simon Christensen
 */
public class CPESubName_c extends ClassnamePatternExpr_c 
    implements CPESubName, ContainsNamePattern
{
    protected NamePattern pat;

    public CPESubName_c(Position pos, NamePattern pat)  {
	super(pos);
        this.pat = pat;
    }

    protected CPESubName_c reconstruct(NamePattern pat) {
	if (pat != this.pat) {
	    CPESubName_c n = (CPESubName_c) copy();
	    n.pat = pat;
	    return n;
	}
	return this;
    }

    public Node visitChildren(NodeVisitor v) {
	NamePattern pat = (NamePattern) visitChild(this.pat, v);
	return reconstruct(pat);
    }

    public Precedence precedence() {
	return Precedence.LITERAL;
    }

    public void prettyPrint(CodeWriter w, PrettyPrinter tr) {
	print(pat,w,tr);
	w.write("+");
    }

    public String toString() {
	return pat+"+";
    }

    public NamePattern getNamePattern() {
	return pat;
    }

    public boolean matches(PatternMatcher matcher, PCNode cl) {
	if (matcher.matchesName(pat, cl)) {
	    return true;
	}
	Set tried = new HashSet();
	tried.add(cl);
	LinkedList worklist = new LinkedList(tried);
	while (!worklist.isEmpty()) {
	    PCNode n = (PCNode)worklist.removeFirst();
	    Iterator pi = n.getParents().iterator();
	    while (pi.hasNext()) {
		PCNode parent = (PCNode)pi.next();
		if (!tried.contains(parent)) {
		    if (matcher.matchesName(pat, parent)) {
			return true;
		    }
		    tried.add(parent);
		    worklist.addLast(parent);
		}
	    }
	}
	return false;
    }

    public boolean equivalent(ClassnamePatternExpr otherexp) {
	if (otherexp.getClass() == this.getClass()) {
	    return (pat.equivalent(((CPESubName)otherexp).getNamePattern()));
	} else return false;
    }
}
