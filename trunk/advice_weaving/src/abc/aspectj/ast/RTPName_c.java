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

import polyglot.ext.jl.ast.Node_c;

import java.util.*;

/**
 * 
 *  @author Oege de Moor
 *  @author Aske Simon Christensen
 */
public class RTPName_c extends Node_c 
    implements RTPName, ContainsNamePattern
{
    protected NamePattern pat;

    public RTPName_c(Position pos, 
                     NamePattern pat)  {
	super(pos);
        this.pat = pat;
    }
    
	/** Reconstruct the pointcut call. */
	protected RTPName_c reconstruct(NamePattern pat) {
		if (this.pat != pat) {
			 RTPName_c n = (RTPName_c) copy();
			 n.pat = pat;
			 return n;
		}
		return this;
	}

	/** Visit the children of the pointcut call. */
	public Node visitChildren(NodeVisitor v) {
		NamePattern pat = (NamePattern)visitChild(this.pat, v);
		return reconstruct(pat);
	}

    public void prettyPrint(CodeWriter w, PrettyPrinter tr) {
	print(pat,w,tr);
    }

    public String toString() {
	return pat.toString();
    }

    public NamePattern getNamePattern() {
	return pat;
    }

    public boolean matchesClass(PatternMatcher matcher, PCNode cl) {
	return matcher.matchesName(pat, cl);
    }

    public boolean matchesArray(PatternMatcher matcher) {
	return false;
    }

    public ClassnamePatternExpr transformToClassnamePattern(AJNodeFactory nf) throws SemanticException {
	return nf.CPEName(position, pat);
    }

    public boolean equivalent(RefTypePattern p) {
	if (p.getClass() == this.getClass()) {
	    return (pat.equivalent(((RTPName)p).getNamePattern()));
	} else return false;
    }

}
