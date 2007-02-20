/* abc - The AspectBench Compiler
 * Copyright (C) 2005 Ondrej Lhotak
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

import polyglot.ast.*;

import polyglot.types.*;
import polyglot.util.*;
import polyglot.visit.*;
import java.util.*;

import abc.aspectj.types.AJContext;
import abc.main.Debug;

/**
 * 
 * @author Ondrej Lhotak
 *
 */
public class PCCflowDepth_c extends PCCflow_c implements PCCflowDepth
{
    protected Local var;

    public PCCflowDepth_c(Position pos, Pointcut pc, Local var)  {
        super(pos, pc);
        this.var = var;
    }

    protected PCCflowDepth_c reconstruct(Pointcut pc, Local var) {
        if(pc != this.pc || var != this.var) {
            PCCflowDepth_c ret = (PCCflowDepth_c) copy();
            ret.pc = pc;
            ret.var = var;
            return ret;
        }
        return this;
    }

    public void prettyPrint(CodeWriter w, PrettyPrinter tr) {
        w.write("cflowdepth("+var);
        w.write(", ");
        print(pc, w, tr);
        w.write(")");
    }

    public abc.weaving.aspectinfo.Pointcut makeAIPointcut() {
	return new abc.weaving.aspectinfo.CflowDepth
	    (pc.makeAIPointcut(), position(),
	    new abc.weaving.aspectinfo.Var(var.name(), var.position()));
    }
    public Collection mayBind() throws SemanticException {
        Collection ret = super.mayBind();
        ret.add(var.name());
        return ret;
    }

    public Collection mustBind() {
        Collection ret = super.mustBind();
        ret.add(var.name());
        return ret;
    }
    public String toString() {
	return "cflowdepth("+var+", "+pc+")";
    }
    public Node visitChildren(NodeVisitor v) {
		 Pointcut pc = (Pointcut) visitChild(this.pc, v);
		 Local var = (Local) visitChild(this.var, v);
		 return reconstruct(pc,var);
    }

    public Context enterScope(Context c)
    {
        AJContext nc = (AJContext) super.enterScope(c);
        nc.getCflowMustBind().remove(var.name());
        return nc;
    }

    public Node typeCheck(TypeChecker tc) throws SemanticException {
        Node ret = super.typeCheck(tc);
        if(!tc.typeSystem().Int().isImplicitCastValid(var.type())) {
            throw new SemanticException("Parameter of cflowdepth must be of type int.");
        }
        return ret;
    }
}
