/* abc - The AspectBench Compiler
 * Copyright (C) 2006 Julian Tibble
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

/* abc - The AspectBench Compiler
 * Copyright (C) 2005 Julian Tibble
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

import polyglot.ext.jl.ast.*;

import abc.aspectj.visit.*;
import abc.aspectj.types.*;

import java.util.*;

/**
 * @author Julian Tibble
 */
public class SymbolDecl_c extends Node_c implements SymbolDecl
{
    final static boolean debug_tm_advice = false;

    private String name;
    private SymbolKind kind;
    private Pointcut pc;

    public SymbolDecl_c(Position pos, String name,
                            SymbolKind kind, Pointcut pc)
    {
        super(pos);
        this.name = name;
        this.kind = kind;
        this.pc = pc;
    }

    public String name()
    {
        return name;
    }

    public Pointcut getPointcut()
    {
        return pc;
    }

    public String kind()
    {
        return kind.kind();
    }
    
    public SymbolKind getSymbolKind()
    {
    	return kind;
    }

    public Collection binds()
    {
        Collection binds = new HashSet(pc.mustBind());
        binds.addAll(kind.binds());
        return binds;
    }

    public Context enterScope(Context c)
    {
        AJContext tmc = (AJContext) c;

        return tmc.pushSymbol(pc.mustBind());
    }

    public Node typeCheck(TypeChecker tc) throws SemanticException
    {
        Node n = super.typeCheck(tc);

        if (kind.kind() == SymbolKind.AROUND)
        {
            Iterator vars = kind.aroundVars().iterator();
            Collection pc_binds = getPointcut().mustBind();
            Collection around_vars = new HashSet();

            while (vars.hasNext()) {
                String name = ((Local) vars.next()).name();

                around_vars.add(name);

                if (!pc_binds.contains(name))
                    throw new SemanticException("Advice formal \"" +
                                name + "\" appears in the list of " +
                                "proceed arguments for an around symbol " +
                                "but is not bound by it.", position());
            }

            vars = pc_binds.iterator();

            while (vars.hasNext()) {
                String name = (String) vars.next();

                if (!around_vars.contains(name))
                    throw new SemanticException("Advice formal \"" + name +
                                "\" is bound by an around symbol but is " +
                                "not in the list of proceed arguments.",
                                position());
            }

        }

        return n;
    }

    // 
    // visitor handling code
    //
    protected Node reconstruct(Node n, SymbolKind kind, Pointcut pc)
    {
        if (kind != this.kind || pc != this.pc)
        {
            SymbolDecl_c new_n = (SymbolDecl_c) n.copy();
            new_n.kind = kind;
            new_n.pc = pc;

            return new_n;
        }
        return n;
    }

    public Node visitChildren(NodeVisitor v)
    {
        Node n = super.visitChildren(v);

        if (v instanceof AspectMethods)
            return reconstruct(n, kind, pc);

        SymbolKind kind = (SymbolKind) visitChild(this.kind, v);
        Pointcut pc = (Pointcut) visitChild(this.pc, v);

        return reconstruct(n, kind, pc);
    }



    public AdviceDecl generateSymbolAdvice(AJNodeFactory nf, List formals,
                                TypeNode voidn, String tm_id, Position tm_pos)
    {
        // Generate AdviceSpec
        AdviceSpec spec = kind.generateAdviceSpec(nf, formals, voidn);

        // Generate an empty `throws' list
        List tlist = new LinkedList();

        // Generate the TMAdviceDecl
        return nf.PerSymbolAdviceDecl(position(), Flags.NONE, spec,
                                tlist, pc, body(nf, name, voidn),
                                tm_id, tm_pos);
    }

    /**
     * Create an empty advice body (contains debug print statement
     * if debug_tm_advice is set).
     */
    public Block body(AJNodeFactory nf, String debug_msg, TypeNode ret_type)
    {
        Position cg = Position.COMPILER_GENERATED;

        List statements = new LinkedList();

        if (debug_tm_advice) {
            AmbReceiver sysout =
                nf.AmbReceiver(cg, nf.AmbPrefix(cg, null, "System"), "out");

            List args = new LinkedList();
            args.add(nf.StringLit(cg, debug_msg));

            Call println_call = nf.Call(cg, sysout, "println", args);

            Stmt println_statement = nf.Eval(cg, println_call);

            statements.add(println_statement);
        }

        if (kind() == SymbolKind.AROUND && !ret_type.type().isVoid())
        {
            Call proceed_call = nf.ProceedCall(cg, nf.This(cg),
                                                new LinkedList());
            Return ret = nf.Return(cg, proceed_call);
            statements.add(ret);
        }

        return nf.Block(cg, statements);
    }

    public Pointcut generateClosedPointcut(AJNodeFactory nf, List formals)
    {
        return nf.ClosedPointcut(pc.position(), formals, pc);
    }

    public List aroundVars()
    {
        return kind.aroundVars();
    }

	/**
	 * Creates a unique and fully qualified ID for a tracematch symbol based on the tracematch
	 * ID and the name of the symbol.
	 * @param tm_id an ID unique to the tracematch
	 * @param symbolName a symbol name unique for the given tracematch
	 * @return a unique, fully qualified ID
	 */
	public static String uniqueSymbolID(String tm_id, String symbolName) {
		return (tm_id + "$" + symbolName).intern();
	}
}
