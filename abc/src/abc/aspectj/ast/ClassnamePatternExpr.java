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
import polyglot.visit.*;
import polyglot.util.*;


/** 
 *  Class name pattern expressions can combine name patterns with logical operators.
 * 
 *  @author Oege de Moor
 *  @author Aske Simon Christensen
 */

public interface ClassnamePatternExpr extends Node
{

    Precedence precedence();

    void printSubExpr(ClassnamePatternExpr expr, boolean associative,
                      CodeWriter w, PrettyPrinter pp);

    public boolean matches(PatternMatcher matcher, PCNode cl);
    
    public boolean matches(PCNode cl);

    public abc.weaving.aspectinfo.ClassnamePattern makeAIClassnamePattern();

    public boolean equivalent(ClassnamePatternExpr e);
}
