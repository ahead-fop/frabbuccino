/* *******************************************************************
 * Copyright (c) 2004 Julian Tibble. 
 * All rights reserved. 
 * This program and the accompanying materials are made available 
 * under the terms of the Common Public License v1.0 
 * which accompanies this distribution and is available at 
 * http://www.eclipse.org/legal/cpl-v10.html 
 *  
 * Contributors: 
 *     Jim Hugunin    initial implementation
 * ******************************************************************/

package org.aspectbench.runtime.reflect;

import org.aspectj.lang.reflect.ThrowSignature;

public class ThrowSignatureImpl extends SignatureImpl
                                implements ThrowSignature
{
    ThrowSignatureImpl(Class declaringType)
    {
        super(0, "throw", declaringType);
    }
    
    ThrowSignatureImpl(String stringRep)
    {
        super(stringRep);
    }

    public String toString(StringMaker sm)
    {
        return "throw";
    } 
}
