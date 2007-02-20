/* *******************************************************************
 * Copyright (c) 1999-2001 Xerox Corporation, 
 *               2002 Palo Alto Research Center, Incorporated (PARC).
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Common Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://www.eclipse.org/legal/cpl-v10.html 
 *  
 * Contributors: 
 *     Xerox/PARC     initial implementation 
 * ******************************************************************/


package org.aspectbench.runtime.reflect;

import org.aspectj.lang.*;

import org.aspectj.lang.reflect.SourceLocation;

public class JoinPointImpl implements JoinPoint {
    // We make StaticPartImpl implement both JoinPoint.StaticPart and JoinPoint,
    // because we optimize some JoinPoints to use StaticPartImpl
    public static class StaticPartImpl implements JoinPoint.StaticPart, JoinPoint {
        String kind;
        Signature signature;
        SourceLocation sourceLocation;

        public StaticPartImpl(String kind, Signature signature, SourceLocation sourceLocation) {
            this.kind = kind;
            this.signature = signature;
            this.sourceLocation = sourceLocation;
        }

        public String getKind() { return kind; }
        public Signature getSignature() { return signature; }
        public SourceLocation getSourceLocation() { return sourceLocation; }

        String toString(StringMaker sm) {
            StringBuffer buf = new StringBuffer();
            buf.append(sm.makeKindName(getKind()));
            buf.append("(");
            buf.append(((SignatureImpl)getSignature()).toString(sm));
            buf.append(")");
            return buf.toString();
        }

        public final String toString() { return toString(StringMaker.middleStringMaker); }
        public final String toShortString() { return toString(StringMaker.shortStringMaker); }
        public final String toLongString() { return toString(StringMaker.longStringMaker); }
        public Object getThis() { throw new RuntimeException("Internal error: The compiler optimized a thisJoinPoint to be static, but it's being called in a dynamic way."); }
        public Object getTarget() { throw new RuntimeException("Internal error: The compiler optimized a thisJoinPoint to be static, but it's being called in a dynamic way."); }
        public Object[] getArgs() { throw new RuntimeException("Internal error: The compiler optimized a thisJoinPoint to be static, but it's being called in a dynamic way."); }

        public org.aspectj.lang.JoinPoint.StaticPart getStaticPart() { return this; }
    }

    Object _this;
    Object target;
    Object[] args;
    org.aspectj.lang.JoinPoint.StaticPart staticPart;

    public JoinPointImpl(org.aspectj.lang.JoinPoint.StaticPart staticPart, Object _this, Object target, Object[] args) {
        this.staticPart = staticPart;
        this._this = _this;
        this.target = target;
        this.args = args;
    }

    public Object getThis() { return _this; }
    public Object getTarget() { return target; }
    public Object[] getArgs() {
        if (args == null) { args = new Object[0]; } // can this ever happen?
        Object[] argsCopy = new Object[args.length];
        System.arraycopy(args,0,argsCopy,0,args.length);
        return argsCopy;
    }

    public org.aspectj.lang.JoinPoint.StaticPart getStaticPart() { return staticPart; }

    public String getKind() { return staticPart.getKind(); }
    public Signature getSignature() { return staticPart.getSignature(); }
    public SourceLocation getSourceLocation() { return staticPart.getSourceLocation(); }

    public final String toString() { return staticPart.toString(); }
    public final String toShortString() { return staticPart.toShortString(); }
    public final String toLongString() { return staticPart.toLongString(); }
}
