/* abc - The AspectBench Compiler
 * Copyright (C) 2005 Pavel Avgustinov
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

package abc.weaving.weaver;

import java.util.*;

import soot.*;
import soot.util.*;
import soot.coffi.parameter_annotation;
import soot.jimple.*;

import abc.main.Debug;
import abc.soot.util.LocalGeneratorEx;
import abc.soot.util.UnUsedParams;
import abc.weaving.matching.*;
import abc.weaving.aspectinfo.*;

/**
 * Helps with the generation of the Constraint and Disjunct classes in Jimple.
 * @author Pavel Avgustinov
 */

public class ClassGenHelper {
    ///////// flags
    // Set this to true to enable debug traces -- print "D" for every time a disjunct
    // is constructed, "d" for every time it's finalized and "*" every time one is
    // discarded for being invalid; also, "C" when a constraint is created and "c"
    // when one is destroyed (those are the default traces).
    // If you pipe the output into a file (by appending '> output_file' to the command
    // line), you can then count the frequency of the respective events with the
    // following command: cat output_file | tr "D" "\n" | wc -l
    // (replacing "D" by "d" or "C" or "c" or "*" as appropriate).
    private boolean enableDebugTraces = false;
    
    private int debugCount = 0;
    /**
     * Insert a debug-trace statement
     */
    private void debug() {
    	LinkedList formals = new LinkedList(), actuals = new LinkedList();
    	formals.add(IntType.v());
    	actuals.add(getInt(debugCount++));
    	doMethodCall(getStaticFieldLocal(Scene.v().getSootClass("java.lang.System"), "out", RefType.v("java.io.PrintStream")), 
    			"print", formals, VoidType.v(), actuals);
    }
    
    /**
     * Dump an Object to stdout.
     */
    private void debug(Local o) {
    	LinkedList formals = new LinkedList(), actuals = new LinkedList();
    	formals.add(objectType);
    	actuals.add(o);
    	doMethodCall(getStaticFieldLocal(Scene.v().getSootClass("java.lang.System"), "out", RefType.v("java.io.PrintStream")), 
    			"print", formals, VoidType.v(), actuals);
    }
   
    
    /**
     * With the intention of providing a -debug switch to turn indexing on and off, all
     * indexing-related inclusions will be guarded by "if(useIndexing())". The body of
     * this method can be adapted to take into account the command-line parameters, 
     * preferably after we have a way of adding extension-specific parameters.
     *  
     * @return <code>true</code> if indexing-related code should be generated.
     */
    private boolean useIndexing() {
            return abc.main.Debug.v().useIndexing;
    }
    
    /**
     * Class to store the context information required to iterate over a mapping. It can be
     * used to both walk an entire mapping and to pick out a specific value, as well as
     * a mixture of the two.
     * 
     * Consider first the case when the entire mapping should be walked, and there is some
     * code that should be executed for every LinkedHashSet in the mapping structure. Use
     * the first constructor (IterationContext(int, Local)). The map will be walked up to
     * the indicated depth; at each level, an iterator of the keyset will be stored into
     * the iterators[] field, the current map for that level will be stored in maps[] and
     * the current key in keys[]. While the iterator.hasNext(), we get the next key and
     * proceed. At the final level, we cast the result of map.get() to setType.
     * 
     * Note that due to a peculiarity of the indexing data structure, if we use the hashmaps
     * from org.aspectbench.runtime.internal, then rather than checking iterator.hasNext()
     * we need to check that iterator.next() != null.
     * 
     * If a particular set of keys is known, these should be passed to the second form of the
     * constructor. A 'null' entry in the keys[] array indicates "iterate over the keyset at
     * this level". A non-null entry means "get the value for this key; if it is non-null,
     * proceed deeper; if it is null, back out to the parent level". 
     */
    class IterationContext {
        public Local[] keys, maps, iterators;
        public Stmt[] loopBegins, loopEnds;
        public Local relevantSet;
        public int depth;
        
        public IterationContext(int depth, Local map) {
            keys = new Local[depth];
            maps = new Local[depth];
            iterators = new Local[depth];
            
            loopBegins = new Stmt[depth];
            loopEnds = new Stmt[depth];
            
            maps[0] = map;
            
            this.depth = depth;
        }
        
        public IterationContext(int depth, Local map, Local[] keys) {
            this(depth, map);
            for(int i = 0; i < depth; i++) {
                this.keys[i] = keys[i];
            }
        }
        
    }

    // Relevant members
    TraceMatch curTraceMatch;
    SootClass constraint, disjunct;
//  static SootClass myWeakRef;
    
    private SootClass curClass;
    private SootMethod curMethod;
    private Body curBody;
    private Chain curUnits;
    private LocalGeneratorEx curLGen;
    
    // often needed class and type constants
    static SootClass objectClass;
    static SootClass setClass;
    static SootClass iteratorClass;
    static SootClass ccMapClass;
    
    static SootClass idMapClass;
    static SootClass weakIdMapClass;
    static SootClass collWeakIdMapClass;
    
    static Type objectType;
    static Type setType;
    static Type jusetType;
    static Type iteratorType;
    static RefType mapType;
    
    // other often-needed constants
    List emptyList = new LinkedList();
    List singleObjectType = new LinkedList();
    List singleCollectionType = new LinkedList();
    
    /**
     * The modifiers with which the new classes are created. Modifier.PUBLIC by default. 
     */
    int classModifiers = Modifier.PUBLIC;
    
    /**
     * A ClassGenHelper is specific to a tracematch -- a lot of the optimisations rely on knowing information
     * about the states of the NFA, quite apart from the fact that what methods are generated depends on
     * the tracematch formals and symbols.
     * @param tm the relevant tracematch
     */
    public ClassGenHelper(TraceMatch tm) {
        curTraceMatch = tm;
        
        // often needed class and type constants
        objectClass = Scene.v().getSootClass("java.lang.Object");
        setClass = Scene.v().getSootClass("java.util.LinkedHashSet");
        iteratorClass = Scene.v().getSootClass("java.util.Iterator");
        objectType = RefType.v("java.lang.Object");
        setType = RefType.v("java.util.LinkedHashSet");
        jusetType = RefType.v("java.util.Set");
        iteratorType = RefType.v("java.util.Iterator");

        if(useIndexing()) {
            if(abc.main.Debug.v().useCommonsCollections) {
                ccMapClass = Scene.v().getSootClass("org.apache.commons.collections.map.ReferenceIdentityMap");
            } else {
            	idMapClass = Scene.v().getSootClass("org.aspectbench.runtime.internal.IdentityHashMap");
            	weakIdMapClass = Scene.v().getSootClass("org.aspectbench.runtime.internal.WeakKeyIdentityHashMap");
            	collWeakIdMapClass = Scene.v().getSootClass("org.aspectbench.runtime.internal.WeakKeyCollectingIdentityHashMap");
            }
        	mapType = RefType.v("java.util.Map");
        }

        singleObjectType.add(objectType);
        singleCollectionType.add(RefType.v("java.util.Collection"));
    }

    /**
     * Wrapper for the actual class generation. Creates a Constraint class and a Disjunct class, and sets
     * the relevant fields of the associated tracematch to the new classes.
     */
    public void generateClasses() {
        // the SootClasses for the constraint and the main disjunct class for the tracematch
        constraint = new SootClass(curTraceMatch.getPackage() + "Constraint$" + curTraceMatch.getName(), classModifiers);
        curTraceMatch.setConstraintClass(constraint);

        disjunct = new SootClass(curTraceMatch.getPackage() + "Disjunct$" + curTraceMatch.getName(), classModifiers);
        curTraceMatch.setDisjunctClass(disjunct);

        fillInConstraintClass();
        fillInDisjunctClass();

        Scene.v().addClass(constraint);
        constraint.setApplicationClass();
        constraint.setSuperclass(objectClass);/*
        constraint.setSuperclass(Scene.v().getSootClass("java.lang.Object"));/**/

        Scene.v().addClass(disjunct);
        disjunct.setApplicationClass();
        disjunct.setSuperclass(objectClass);/*
        disjunct.setSuperclass(Scene.v().getSootClass("java.lang.Object"));/**/
    }
    
    //////////////// General Jimple manipulation functions
    
    /**
     * Marks a SootClass as the current target for startMethod() calls.
     * @param cl The class for which members will be generated next.
     */
    protected void startClass(SootClass cl) {
        curClass = cl;
    }
    
    /**
     * Start the generation of a SootMethod. This method should be called once to set up the internal state
     * so that further calls to code generation methods generate code in the right place. It creates a method
     * of the given name with the given parameter types, return type and modifiers and adds it to the 
     * currently active class (as set with startClass).
     * @param name The name of the methodd
     * @param params List containing the Types of parameters
     * @param returnType Type which the method will return
     * @param modifiers Modifiers for the method
     */
    protected void startMethod(String name, List params, Type returnType, int modifiers) {
        curMethod = new SootMethod(name, params, returnType, modifiers);
        curBody = Jimple.v().newBody(curMethod);
        curMethod.setActiveBody(curBody);
        curClass.addMethod(curMethod);
        curLGen = new LocalGeneratorEx(curBody);
        curUnits = curBody.getUnits();
    }
    
    /**
     * Returns a Jimple null constant
     */
    protected Value getNull() {
        return NullConstant.v();
    }
    
    /**
     * Returns a Jimple int constant for the given int value. Also used for Bools -- getInt(1) is true,
     * getInt(0) is false.
     */
    protected Value getInt(int n) {
        return IntConstant.v(n);
    }
    
    /**
     * Returns a Jimple String constant with the given value.
     */
    protected Value getString(String s) {
        return StringConstant.v(s);
    }
    
    /**
     * Returns a singleton list containing its argument.
     */
    protected List getList(Object o) {
        List result = new LinkedList();
        result.add(o);
        return result;
    }
    
    /**
     * Constructs 'local := @caughtexception. Only valid after a handler label.
     */
    protected Local getCaughtExceptionLocal() {
        Local caughtLocal = curLGen.generateLocal(curClass.getType(), "caughtException");
        curUnits.addLast(Jimple.v().newIdentityStmt(caughtLocal, Jimple.v().newCaughtExceptionRef()));
        return caughtLocal;
    }
    
    /**
     * This should be called only once per startMethod() call. It returns a local that holds a reference to
     * 'this', via an Identity statement.
     */
    protected Local getThisLocal() {
        Local thisLocal = curLGen.generateLocal(curClass.getType(), "thisLocal");
        curUnits.addLast(Jimple.v().newIdentityStmt(thisLocal, Jimple.v().newThisRef(curClass.getType())));
        return thisLocal;
    }
    
    /**
     * This should be called only once per startMethod() call and parameter. It returns a local of the given
     * type, holding the parameter with the given index.
     * @param index Index of the required parameter
     * @param type the type of the parameter and the resulting local -- should match the method declaration
     * @return
     */
    protected Local getParamLocal(int index, Type type) {
        Local paramLocal = curLGen.generateLocal(type, "paramLocal" + index);
        curUnits.addLast(Jimple.v().newIdentityStmt(paramLocal, Jimple.v().newParameterRef(type, index)));
        return paramLocal;
    }
    
    /**
     * constructs a weak reference to the given value. It is assumed that the weak reference is intended for 
     * the tracematch formal of type varName, so the corresponding weakBindingClass is used.
     */
    protected Local getWeakRef(Value to, String varName) {
        return getNewObject(curTraceMatch.weakBindingClass(varName), 
                getList(curTraceMatch.weakBindingConstructorArgType(varName)), to);
    }
    /**
     * Returns a new Nop to be used as a jump label
     */
    protected Stmt getNewLabel() {
        return Jimple.v().newNopStmt();
    }
    
    /**
     * Returns a new local for the current method body.
     * @param type type of the local
     * @param value initial value to assign to it
     * @param nameHint a hint for the naming of the local -- the name will be nameHint + "$" + <uniqueNumber>.
     */
    protected Local getNewLocal(Type type, Value value, String nameHint) {
        Local result = curLGen.generateLocal(type, nameHint);
        curUnits.addLast(Jimple.v().newAssignStmt(result, value));
        return result;
    }
    
    /**
     * Returns a local of a given type that contains the value of the variable cast to that type.
     * @param var the value to cast
     * @param type the type to cast to
     */
    protected Local getCastValue(Local var, Type type) {
        Local result = curLGen.generateLocal(type, "castResult");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newCastExpr(var, type)));
        return result;
    }
    
    /**
     * Returns a local of type returnType containing the result of the method call. The method is called
     * with target as the receiver, and the Type of target (assumed to be a RefType) is used to retrieve
     * the SootClass in which the method is defined. The method is assumed to have no parameters.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param returnType Return type of the method -- also type of the local to be returned
     * @return A local of type returnType containing the return value of the method call.
     */
    protected Local getMethodCallResult(Local target, String name, Type returnType) {
        Local result = curLGen.generateLocal(returnType, name + "$result");
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, emptyList, returnType, false))));
        } else {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, emptyList, returnType, false))));
        }
        return result; 
    }
    
    /**
     * Returns a local of type returnType containing the result of the method call. The method is called
     * with target as the receiver, and the Type of target (assumed to be a RefType) is used to retrieve
     * the SootClass in which the method is defined.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param formals List of types of the formal parameters -- should contain one member
     * @param returnType Return type of the method -- also type of the local to be returned
     * @param arg The local to be passed as a parameter
     * @return A local of type returnType containing the return value of the method call.
     */
    protected Local getMethodCallResult(Local target, String name, List formals, Type returnType, Local arg) {
        Local result = curLGen.generateLocal(returnType, name + "$result");
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), arg)));
        } else {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), arg)));
        }
        return result;
    }
    
    /**
     * Returns a local of type returnType containing the result of the method call. The method is called
     * with target as the receiver, and the Type of target (assumed to be a RefType) is used to retrieve
     * the SootClass in which the method is defined.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param formals List of types of the formal parameters
     * @param returnType Return type of the method -- also type of the local to be returned
     * @param actuals List containing locals with the parameter values that should be passed to the method.
     * @return A local of type returnType containing the return value of the method call.
     */
    protected Local getMethodCallResult(Local target, String name, List formals, Type returnType, List actuals) {
        Local result = curLGen.generateLocal(returnType, name + "$result");
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), actuals)));
        } else {
            curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), actuals)));
        }
        return result;
    }
    
    /**
     * Returns a local of type returnType containing the result of the method call. The method is called
     * statically on the provided SootClass. The method is assumed to have no parameters.
     * @param cl the class containing the method
     * @param name The name of the method
     * @param returnType Return type of the method -- also type of the local to be returned
     * @return A local of type returnType containing the return value of the method call.
     */
    protected Local getStaticMethodCallResult(SootClass cl, String name, Type returnType) {
        Local result = curLGen.generateLocal(returnType, name + "$result");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(cl, name, emptyList, returnType, true))));
        return result; 
    }
    
    /**
     * Returns a local of type returnType containing the result of the method call. The method is called
     * statically on the provided SootClass.
     * @param cl the class defining the method.
     * @param name The name of the method
     * @param formals List of types of the formal parameters -- should contain one member
     * @param returnType Return type of the method -- also type of the local to be returned
     * @param arg The local to be passed as a parameter
     * @return A local of type returnType containing the return value of the method call.
     */
    protected Local getStaticMethodCallResult(SootClass cl, String name, List formals, Type returnType, Local arg) {
        Local result = curLGen.generateLocal(returnType, name + "$result");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newStaticInvokeExpr(
                Scene.v().makeMethodRef(cl, name, formals, returnType, true), arg)));
        return result;
    }
    
    /**
     * Returns a local containing a reference to a newly constructed object of the given class.
     */
    protected Local getNewObject(SootClass cl) {
        Local result = curLGen.generateLocal(cl.getType(), "newObject");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newNewExpr(cl.getType())));
        doConstructorCall(result, cl);
        return result;
    }
    
    /**
     * Returns a local containing a reference to a newly constructed object of the given class. The constructor
     * with parameter list 'formals' is used (this should contain one type only), and 'arg' is passed as an
     * actual parameter
     * @param cl type to construct
     * @param formals list of one type -- the type of the single parameter in the constructor signature
     * @param arg actual value to pass to the constructor
     */
    protected Local getNewObject(SootClass cl, List formals, Value arg) {
        Local result = curLGen.generateLocal(cl.getType(), "newObject");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newNewExpr(cl.getType())));
        doConstructorCall(result, cl, formals, arg);
        return result;
    }
    
    /**
     * Returns a local containing a reference to a newly constructed object of the given class. The constructor
     * with parameter list 'formals' is used, and 'actuals' is passed as parameter values.
     * @param cl type to construct
     * @param formals list of parameter types
     * @param actuals list of actual parameter values
     */
    protected Local getNewObject(SootClass cl, List formals, List actuals) {
        Local result = curLGen.generateLocal(cl.getType(), "newObject");
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newNewExpr(cl.getType())));
        doConstructorCall(result, cl, formals, actuals);
        return result;
    }
    
    /**
     * Returns a local containing a reference to an instance field with target 'target'. The class in which the
     * field is declared is determined from the type of the 'target' local.
     * @param target the receiver of the field access
     * @param name the name of the field
     * @param type the type of the field
     * @return
     */
    protected Local getFieldLocal(Local target, String name, Type type) {
        Local result = curLGen.generateLocal(type, name + "$local");
        SootFieldRef fieldRef = Scene.v().makeFieldRef(((RefType)target.getType()).getSootClass(), name, type, false);
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newInstanceFieldRef(target, fieldRef)));
        return result;
    }
    
    /**
     * Returns a local containing a reference to a static field of the class cl.
     * @param cl the class in which the field is declared
     * @param name the name of the field
     * @param type the type of the field
     */
    protected Local getStaticFieldLocal(SootClass cl, String name, Type type) {
        Local result = curLGen.generateLocal(type, name + "$local");
        SootFieldRef fieldRef = Scene.v().makeFieldRef(cl, name, type, true);
        curUnits.addLast(Jimple.v().newAssignStmt(result, Jimple.v().newStaticFieldRef(fieldRef)));
        return result;
    }
    
    /**
     * Constructs a new java.util.HashMap.
     */
    protected Local getNewHashMap() {
    	return getNewObject(Scene.v().getSootClass("java.util.HashMap"));
    }

    /**
     * Constructs a new map based on object identity with hard keys and values.
     */
    protected Local getNewIdMap() {
    	if(abc.main.Debug.v().useCommonsCollections) {
    		LinkedList formals = new LinkedList(), actuals = new LinkedList();
    		formals.add(IntType.v());
    		formals.add(IntType.v());
    		Local hard = getStaticFieldLocal(Scene.v().getSootClass("org.apache.commons.collections.map.AbstractReferenceMap"), 
                    "HARD", IntType.v());
    		actuals.add(hard);
    		actuals.add(hard);
    		return getNewObject(ccMapClass, formals, actuals);
    	} else {
    		return getNewObject(idMapClass);
    	}
    }
    
    /**
     * Constructs a new map based on object identity with weak keys and hard values values.
     */
    protected Local getNewWeakIdMap() {
    	if(abc.main.Debug.v().useCommonsCollections) {
    		LinkedList formals = new LinkedList(), actuals = new LinkedList();
    		formals.add(IntType.v());
    		formals.add(IntType.v());
    		actuals.add(getStaticFieldLocal(Scene.v().getSootClass("org.apache.commons.collections.map.AbstractReferenceMap"), 
                    "WEAK", IntType.v()));
    		actuals.add(getStaticFieldLocal(Scene.v().getSootClass("org.apache.commons.collections.map.AbstractReferenceMap"), 
                    "HARD", IntType.v()));
    		return getNewObject(ccMapClass, formals, actuals);
    	} else {
    		return getNewObject(weakIdMapClass);
    	}
    }
    
    /**
     * Constructs a new map based on object identity with collectable weak keys and hard values.
     */
    protected Local getNewCollWeakIdMap() {
    	if(abc.main.Debug.v().useCommonsCollections) {
    		LinkedList formals = new LinkedList(), actuals = new LinkedList();
    		formals.add(IntType.v());
    		formals.add(IntType.v());
    		formals.add(BooleanType.v());
    		actuals.add(getStaticFieldLocal(Scene.v().getSootClass("org.apache.commons.collections.map.AbstractReferenceMap"), 
                    "WEAK", IntType.v()));
    		actuals.add(getStaticFieldLocal(Scene.v().getSootClass("org.apache.commons.collections.map.AbstractReferenceMap"), 
                    "HARD", IntType.v()));
    		actuals.add(getInt(1));
    		return getNewObject(ccMapClass, formals, actuals);
    	} else {
    		return getNewObject(collWeakIdMapClass);
    	}
    }
    
    /**
     * Constructs a new map, using a type of map appropriate for the constraint and nesting
     * depth.
     * 
     * @param thisLocal A local holding the this pointer to the constraint object
     * @param depth The desired nesting depth of the new map
     * @return a local containing the new object.
     */
    protected Local getNewMapForDepth(Local thisLocal, Value depth) {
        Local result = curLGen.generateLocal(mapType, "map$");
        
        Stmt labelUseCollMap = getNewLabel();
        Stmt labelUseHashMap = getNewLabel();
        Stmt labelUseWeakMap = getNewLabel();
        Stmt labelEnd = getNewLabel();
        
        doJumpIfGreater(getFieldLocal(thisLocal, "collectableUntil", IntType.v()), depth, labelUseCollMap);
        
        doJumpIfGreater(getFieldLocal(thisLocal, "primitiveUntil", IntType.v()), depth, labelUseHashMap);
        
        doJumpIfGreater(getFieldLocal(thisLocal, "weakUntil", IntType.v()), depth, labelUseWeakMap);
        
        doAssign(result, getNewIdMap());
        doJump(labelEnd);
        
        doAddLabel(labelUseCollMap);
        doAssign(result, getNewCollWeakIdMap());
        doJump(labelEnd);
        
        doAddLabel(labelUseHashMap);
        doAssign(result, getNewHashMap());
        doJump(labelEnd);
        
        doAddLabel(labelUseWeakMap);
        doAssign(result, getNewWeakIdMap());
        doJump(labelEnd);
        
        doAddLabel(labelEnd);
        
        return result;
    }
    
    /**
     * Arithmetic helper function -- adds 'value' to the (primitive numeric type'd) 'local' and stores the
     * result in 'local'
     * @param local variable to add to
     * @param value value to add
     */
    protected void doAddToLocal(Local local, Value value) {
        curUnits.addLast(Jimple.v().newAssignStmt(local, Jimple.v().newAddExpr(local, value)));
    }
    
    /**
     * Assigns a value to a local.
     * @param local the variable to assign to
     * @param value the new value of the variable
     */
    protected void doAssign(Local local, Value value){
            curUnits.addLast(Jimple.v().newAssignStmt(local, value));
    }
    
    /**
     * Call a method with given name and return type on target. The method is assumed to have no arguments.
     * Any return value is discarded.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param returnType The return type of the method
     */
    protected void doMethodCall(Local target, String name, Type returnType) {
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, emptyList, returnType, false))));
        } else {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, emptyList, returnType, false))));
        }
    }
    
    /**
     * Call a method with given name and return type on target. The method is assumed to have exactly one
     * argument, arg. Any return value is discarded.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param formals List of types for formal parameters -- assumed to contain one element
     * @param returnType The return type of the method
     * @param arg The single parameter that is passed to the method
     */
    protected void doMethodCall(Local target, String name, List formals, Type returnType, Value arg) {
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), arg)));
        } else {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), arg)));
        }
    }
    
    /**
     * Call a method with given name and return type on target. The method is assumed to have parameters of
     * types contained in formals, actuals is the list of actual parameters. Any return value is discarded.
     * @param target The receiver of the call
     * @param name The name of the method
     * @param formals List of types for formal parameters
     * @param returnType The return type of the method
     * @param actuals The list of values to pass to the method
     */
    protected void doMethodCall(Local target, String name, List formals, Type returnType, List actuals) {
        SootClass cl = ((RefType)target.getType()).getSootClass();
        if(cl.isInterface()) {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newInterfaceInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), actuals)));
        } else {
            curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newVirtualInvokeExpr(target,
                    Scene.v().makeMethodRef(cl, name, formals, returnType, false), actuals)));
        }
    }
    
    /**
     * Does a specialinvoke of the constructor with no parameters of class 'cl', using 'target' as the receiver.
     * Useful for calling superconstructors in class initialisers.
     */
    protected void doConstructorCall(Local target, SootClass cl) {
        curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(target,
                Scene.v().makeConstructorRef(cl, emptyList))));
    }
    
    /**
     * Does a specialinvoke of the constructor with parameters 'formals' of class 'cl', using 'target' as the receiver.
     * Useful for calling superconstructors in class initialisers. 'formals' should be a list containing a single type,
     * 'arg' should be a value of that type -- it is passed as an actual parameter.
     */
    protected void doConstructorCall(Local target, SootClass cl, List formals, Value arg) {
        curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(target,
                Scene.v().makeConstructorRef(cl, formals), arg)));
    }
    
    /**
     * Does a specialinvoke of the constructor with parameters 'formals' of class 'cl', using 'target' as the receiver.
     * Useful for calling superconstructors in class initialisers. 'actuals' is a list of values passed as actual
     * parameters to the constructor.
     */
    protected void doConstructorCall(Local target, SootClass cl, List formals, List actuals) {
        curUnits.addLast(Jimple.v().newInvokeStmt(Jimple.v().newSpecialInvokeExpr(target,
                Scene.v().makeConstructorRef(cl, formals), actuals)));
    }
    
    /**
     * Inserts a jump to the given label at the end of the current method body.
     */
    protected void doJump(Stmt label) {
        curUnits.addLast(Jimple.v().newGotoStmt(label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if val1 and val2 are equal (w.r.t. ==).
     */
    protected void doJumpIfEqual(Value val1, Value val2, Stmt label) {
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newEqExpr(val1, val2), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if val1 and val2 are equal (w.r.t. ==).
     */
    protected void doJumpIfGreater(Value val1, Value val2, Stmt label) {
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newGtExpr(val1, val2), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if val1 and val2 are NOT equal (w.r.t. ==).
     */
    protected void doJumpIfNotEqual(Value val1, Value val2, Stmt label) {
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newNeExpr(val1, val2), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if val is null.
     */
    protected void doJumpIfNull(Value val, Stmt label) {
        doJumpIfEqual(val, NullConstant.v(), label);
    }
    
    /**
     * Inserts a conditional jump to the given label that occurs if both Value parameters are null.
     
    protected void doJumpIfBothNull(Value val1, Value val2, Stmt label) {
        Stmt labelNoJump = getNewLabel();
        doJumpIfNotNull(val1, labelNoJump);
        doJumpIfNull(val2, label);
        doAddLabel(labelNoJump);
    }*/
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if val is NOT null.
     */
    protected void doJumpIfNotNull(Value val, Stmt label) {
        doJumpIfNotEqual(val, NullConstant.v(), label);
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if var is an instance of type.
     */
    protected void doJumpIfInstanceOf(Local var, Type type, Stmt label) {
        Local booleanLocal = curLGen.generateLocal(BooleanType.v(), "booleanLocal");
        curUnits.addLast(Jimple.v().newAssignStmt(booleanLocal, Jimple.v().newInstanceOfExpr(var, type)));
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newEqExpr(booleanLocal, IntConstant.v(1)), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if var is NOT an instance of type.
     */
    protected void doJumpIfNotInstanceOf(Local var, Type type, Stmt label) {
        Local booleanLocal = curLGen.generateLocal(BooleanType.v(), "booleanLocal");
        curUnits.addLast(Jimple.v().newAssignStmt(booleanLocal, Jimple.v().newInstanceOfExpr(var, type)));
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newEqExpr(booleanLocal, IntConstant.v(0)), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if bool (which should be a boolean local) contains true.
     */
    protected void doJumpIfTrue(Local bool, Stmt label) {
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newEqExpr(bool, getInt(1)), label));
    }
    
    /**
     * Inserts a conditional jump to the given label at the end of the current method body -- the jump is performed
     * if bool (which should be a boolean local) contains false.
     */
    protected void doJumpIfFalse(Local bool, Stmt label) {
        curUnits.addLast(Jimple.v().newIfStmt(Jimple.v().newEqExpr(bool, getInt(0)), label));
    }
    
    /**
     * Inserts a lookup switch at the end of the current method body. The switch is on the value of 'key', with 
     * comparison values 'values' and corresponding jumps to 'labels'. If no value matches, jump to defaultLabel.
     */
    protected void doLookupSwitch(Local key, List values, List labels, Stmt defaultLabel) {
        curUnits.addLast(Jimple.v().newLookupSwitchStmt(key, values, labels, defaultLabel));
    }
    
    /**
     * Set the value of an instance field.
     * @param target receiver of the field access -- type determines in which class the field is searched
     * @param name the name of the field
     * @param type the type of the field
     * @param value the value to assign to the field
     */
    protected void doSetField(Local target, String name, Type type, Value value) {
        SootFieldRef fieldRef = Scene.v().makeFieldRef(((RefType)target.getType()).getSootClass(), name, type, false);
        curUnits.addLast(Jimple.v().newAssignStmt(Jimple.v().newInstanceFieldRef(target, fieldRef), value));
    }
    
    /**
     * Set the value of a static field.
     * @param cl the class which declares the field
     * @param name the name of the field
     * @param type the type of the field
     * @param value the value to assign to the field
     */
    protected void doSetStaticField(SootClass cl, String name, Type type, Value value) {
        SootFieldRef fieldRef = Scene.v().makeFieldRef(cl, name, type, true);
        curUnits.addLast(Jimple.v().newAssignStmt(Jimple.v().newStaticFieldRef(fieldRef), value));
    }
    
    /**
     * Inserts the given label at the end of the current method body.
     */
    protected void doAddLabel(Stmt label) {
        curUnits.addLast(label);
    }
    
    /**
     * Inserts a return statement at the end of the current method body -- returns 'val'.
     */
    protected void doReturn(Value val) {
        curUnits.addLast(Jimple.v().newReturnStmt(val));
    }
    
    /**
     * Inserts a 'void return' statement at the end of the current method body (for methods of void return type).
     */
    protected void doReturnVoid() {
        curUnits.addLast(Jimple.v().newReturnVoidStmt());
    }
    
    /**
     * Inserts code to throw a run-time exception with message s at the end of the current method body.
     */
    protected void doThrowException(String s) {
        // exception = new RuntimeException("Attempt to get an unbound variable");
        List singleString = new LinkedList();
        singleString.add(RefType.v("java.lang.String"));
        curUnits.addLast(Jimple.v().newThrowStmt(getNewObject(
                Scene.v().getSootClass("java.lang.RuntimeException"), singleString, StringConstant.v(s))));
    }
    
    /**
     * Generates the 'top half' of the map iteration code required to walk over all sets of an
     * indexed Constraint. See comments on IterationContext for details.
     * @param context the IterationContext in which to store the information about this iteration
     */    
    protected void startIteration(IterationContext context) {
        for(int i = 0; i < context.depth; i++) {
            context.loopEnds[i] = getNewLabel();
            if(context.keys[i] == null) {
                context.iterators[i] = getMethodCallResult(
                        getMethodCallResult(context.maps[i], "keySet", jusetType),
                        "iterator", iteratorType);
                context.loopBegins[i] = getNewLabel();
                doAddLabel(context.loopBegins[i]);
            	doJumpIfFalse(getMethodCallResult(context.iterators[i], "hasNext", 
            			BooleanType.v()), context.loopEnds[i]);
            	context.keys[i] = getMethodCallResult(context.iterators[i], "next", objectType);

            	if(!Debug.v().useCommonsCollections) {
	            	// If we're using our custom (and correct!) indexing data structure maps,
	            	// then the result of hasNext() is unreliable -- the key/value pair could
	            	// have expired in the meantime. Thus, we check the result for nullness.
	            	doJumpIfNull(context.keys[i], context.loopEnds[i]);
            	}

            	if(i + 1 < context.depth) {
                    context.maps[i + 1] = getCastValue(
                            getMethodCallResult(context.maps[i], "get", singleObjectType, 
                            objectType, context.keys[i]), mapType);
                } else {
                    context.relevantSet = getCastValue(
                        getMethodCallResult(context.maps[context.depth - 1], "get", 
                        singleObjectType, objectType, context.keys[context.depth - 1]), setType); 
                }
            } else {
                if(i + 1 < context.depth) {
                    context.maps[i + 1] = getCastValue(
                            getMethodCallResult(context.maps[i], "get", singleObjectType, 
                            objectType, context.keys[i]), mapType);
                    doJumpIfNull(context.maps[i + 1], context.loopEnds[i]);
                } else {
                    context.relevantSet = getCastValue(
                            getMethodCallResult(context.maps[context.depth - 1], "get", 
                            singleObjectType, objectType, context.keys[context.depth - 1]), setType);
                    doJumpIfNull(context.relevantSet, context.loopEnds[i]);
                }
            }
        }
    }
    
    /**
     * Gets a local containing the 'relevant set' in the middle of the iteration identified by
     * context. Note that this value will never be null.
     * @param context the IterationContext that was passed to startIteration
     * @return a Local of type setType containing the current set
     */
    protected Local getRelevantSet(IterationContext context) {
        return context.relevantSet;
    }
    
    /**
     * Finishes off the nested loop structure started by startIteration() by adding jumps to
     * the loopBegin labels (if needed) and the loopEnd labels in reverse order. This version
     * does cleanup after every iteration, removing emtpy sets and maps
     * @param context the IterationContext that was passed to startIteration()
     */
    protected void endIteration(IterationContext context) {
    		endIteration(context, true);
    }
    
    /**
     * Finishes off the nexted loop structure started by startIteration() by adding jumps to
     * the loopBegin labels (if needed) and the loopEnd labels in reverse order. If 'cleanup'
     * is true, empty maps/sets are checked for and removed from the mapping.
     * @param context the IterationContext that was passed to startIteration
     * @param cleanup true iff empty maps/sets should be removed.
     */
    protected void endIteration(IterationContext context, boolean cleanup) {
        for(int i = context.depth - 1; i >= 0; i--) {
        	if(cleanup) {
	            // We only have a real loop if loopBegins[i] isn't null; otherwise we just looked 
	            // a specific key and don't need to jump back.
	            if(context.loopBegins[i] != null) {
	                // cleanup: If the map or set has become empty, we remove it. We iterated over
	                // a keyset at level i, so we need to use iterators[i].remove() to drop the mapping
	                // if necessary -- to avoid disturbing the iterator.
	                if(i == context.depth-1) {
	                    doJumpIfFalse(getMethodCallResult(context.relevantSet, "isEmpty", BooleanType.v()),
	                            context.loopBegins[i]);
	                } else {
	                    doJumpIfFalse(getMethodCallResult(context.maps[i+1], "isEmpty", BooleanType.v()), 
	                            context.loopBegins[i]);
	                }
	                doMethodCall(context.iterators[i], "remove", VoidType.v());
	                // Since context.loopBegins[i] != null, we jump back to the beginning of the loop!
	                doJump(context.loopBegins[i]);
	            } else {
	                // cleanup: If the map or set has become empty, we remove it. We specified key[i]
	                // without iterating, so we can just directly drop the mapping
	                if(i == context.depth-1) {
	                    doJumpIfFalse(getMethodCallResult(context.relevantSet, "isEmpty", BooleanType.v()),
	                            context.loopEnds[i]);
	                } else {
	                    doJumpIfFalse(getMethodCallResult(context.maps[i+1], "isEmpty", BooleanType.v()),
	                            context.loopEnds[i]);
	                }
	                doMethodCall(context.maps[i], "remove", singleObjectType, objectType, 
	                        context.keys[i]);
	            }
        	} else {
        		// no cleanup -- just jump back to beginning of loop
        		if(context.loopBegins[i] != null) {
        			doJump(context.loopBegins[i]);
        		}
        	}
        	
            doAddLabel(context.loopEnds[i]);
        }
    }
    
    /**
     * Inserts code to print s to stdout at the end of the current method body.
     */
    protected void doPrintString(String s) {
        if(enableDebugTraces) {
            List singleString = new LinkedList();
            singleString.add(RefType.v("java.lang.String"));
            Local out = getStaticFieldLocal(Scene.v().getSootClass("java.lang.System"), "out", 
                    RefType.v("java.io.PrintStream"));
            doMethodCall(out, "print", singleString, VoidType.v(), getString(s));
        }
    }
    
    /**
     * Hook to perform 'raw' Jimple code -- i.e. a fragment of code that doesn't justify a higher-level
     * method. Just appends it to the current method's body.
     * @param stmt The Jimple statement to be appended.
     */
    protected void doRawJimple(Stmt stmt) {
        curUnits.addLast(stmt);
    }
    
    //////////////// Generation of the Constraint class
    /**
     * General ideas for the constraint class are as follows:
     * - A constraint gives a set of (positive or negative) bindings which need to hold for the constraint
     *   to be true. The constraint is stored in disjunctive normal form, i.e. as a disjunction of "disjuncts".
     *   Thus, a constraint holds a set of disjuncts and some methods to manipulate them.
     * - The constraint has two static fields -- trueC and falseC -- which represent the 'true' and 'false'
     *   constraints.
     *   
     * This method fills in the members and methods of the constraint class.
     * 
     * Here an outline of what is generated, assuming the Tracematch is called 'tm':
     * 
     * public class Constraint$tm {
     *      static final Constraint$tm trueC = new Constraint$tm((new LinkedHashSet()).add(new Disjunct()));
     *      static final Constraint$tm falseC = new Constraint$tm();
     *      public LinkedHashSet disjuncts;;
     * 
     *      public Constraint();
     *      public Constraint(LinkedHashSet s);
     *      protected void finalize();
     *      public Constraint$tm or(Constraint$tm arg);
     *      public Constraint$tm copy();
     *      public Disjunct$tm[] getDisjunctArray();
     * 
     *      // for each symbol X
     *      public Constraint$tm addBindingsForSymbolX(... bindings ...);
     *      public Constraint$tm addNegativeBindingsForSymbolX(... bindings ...);;
     */
    protected void fillInConstraintClass() {
        startClass(constraint);
        if(useIndexing()) {
            addIndConstraintClassMembers();
            addIndConstraintInitialiser();
            addIndConstraintStaticInitialiser();
            addIndConstraintFinalizeMethod();
            addIndConstraintHelperMethods();
            addIndConstraintGetTrueMethod();
            addIndConstraintMergeMethod();
            addIndConstraintGetDisjunctArrayMethod();
            addIndConstraintGetBindingsMethods();
            if(!abc.main.Debug.v().noNegativeBindings) 
                addIndConstraintQueueNegativeBindingsMethods();
        } else {
            addConstraintClassMembers();
            addConstraintInitialiser();
            addConstraintStaticInitialiser();
            addConstraintFinalizeMethod();
            addConstraintOrMethod();
            addConstraintCopyMethod();
            addConstraintGetDisjunctArrayMethod();
            addConstraintAddBindingsMethods();
            if(!abc.main.Debug.v().noNegativeBindings)
                    addConstraintAddNegativeBindingsMethods();
        }
    }
    
    /**
     * Add fields to the Constraint class. As it stands, there are only three fields: trueC and 
     * falseC, the public static singleton true and false constraints, and disjuncts, a 
     * LinkedHashSet storing the disjuncts in a constraint.
     * 
     * I have a feeling that using static singletons for true and false constraints isn't optimal,
     * one could, say, use flags.
     */
    protected void addConstraintClassMembers() {
        SootField trueConstraint = new SootField("trueC", constraint.getType(), Modifier.PUBLIC | 
                Modifier.FINAL | Modifier.STATIC);
        constraint.addField(trueConstraint);
        SootField falseConstraint = new SootField("falseC", constraint.getType(), Modifier.PUBLIC |
                        Modifier.FINAL | Modifier.STATIC);
        constraint.addField(falseConstraint);
        SootField disjuncts = new SootField("disjuncts", setType,
                        Modifier.PUBLIC);
        constraint.addField(disjuncts);
    }
    
    /**
     * We provide just a basic constructor that allocates the disjuncts set at the moment.
     * 
     * Also, there is a constructor that takes a set and uses that set as the disjunct set.
     * 
     * If debugging is enabled, constructors also print a single 'C' character to stdout.
     * 
     * public Constraint$tm() {
     *      super();
     *      this.disjuncts = new LinkedHashSet();
     *      if(debugging) System.out.print("C");
     * }
     * 
     * public Constraint$tm(LinkedHashSet s) {
     *      super();
     *      this.disjuncts = s;
     *      if(debugging) System.out.print("C");
     * }
     */
    protected void addConstraintInitialiser() {
        
        ////////// no-argument constructor
        startMethod(SootMethod.constructorName, emptyList, VoidType.v(), Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        
        // call super() -- TODO - do we need to do this?
        doConstructorCall(thisLocal, objectClass);
        
        // Construct the new set and store it in this.disjuncts
        doSetField(thisLocal, "disjuncts", setType, getNewObject(setClass));
        
        // For debugging -- print a "C" for constraint construction
        doPrintString("C");
        
        doReturnVoid();
        
        
        ////////// single-argument constructor (taking a set)
        List singleSet = new LinkedList();
        singleSet.add(setType);
        startMethod(SootMethod.constructorName, singleSet, VoidType.v(), Modifier.PUBLIC);
        
        thisLocal = getThisLocal();
        doConstructorCall(thisLocal, objectClass);
        doSetField(thisLocal, "disjuncts", setType, getParamLocal(0, setType));
        doPrintString("C");
        doReturnVoid();
    }
    
    /**
     * The static initialiser of the constraint class simply sets the static fields (trueC, falseC), and
     * ensures that trueC contains an empty disjunct -- this is necessary to make the methods work correctly. 
     */
    protected void addConstraintStaticInitialiser() {
        startMethod(SootMethod.staticInitializerName, emptyList, VoidType.v(), Modifier.PUBLIC);
        
        List singleSet = new LinkedList();
        singleSet.add(setType);

        // Need to initialise static members -- trueC and falseC.
        // trueC should contain a single empty disjunct
        Local tempSet = getNewObject(setClass);
        doMethodCall(tempSet, "add", singleObjectType, BooleanType.v(), getNewObject(disjunct));
        doSetStaticField(constraint, "trueC", constraint.getType(), getNewObject(constraint, singleSet, tempSet));
        doSetStaticField(constraint, "falseC", constraint.getType(), getNewObject(constraint));
        doReturnVoid();
    }
    
    /**
     * If debugging is enabled, finalizing a constraint prints a single 'c' character on stdout.
     * 
     * protected void finalize() {
     *      if(debugging) System.out.print("c");
     * }
     */
    protected void addConstraintFinalizeMethod() {
        startMethod("finalize", emptyList, VoidType.v(), Modifier.PROTECTED);
        // For debugging -- print a "c" for constraint destruction
        doPrintString("c");
        doReturnVoid();
    }
    
    /**
     * Add the method Constraint or(Constraint arg);
     * 
     * The intended behaviour is this:
     * - if either this or arg is trueC, the result is true.
     * - if this is falseC, the result is a copy of arg.
     * - if arg is falseC, the result is this.
     * - Otherwise, the result is this, after the disjuncts from arg have been added to the disjunct set.
     * 
     * Note that this update is DESTRUCTIVE -- we deviate from the 'functional' modification of constraints
     * for the or() method. The reason is rather subtle and follows from the fact that or() is only ever 
     * called on temporary constraint labels, so that it doesn't matter if we modify them destructively --
     * only after the temporary labels become permanent do we need to worry about doing things functionally.
     * 
     * public Constraint$tm or(Constraint$tm param) {
     *      if(this == trueC) goto returnTrue;
     *      if(param == trueC) goto returnTrue;
     *      // order is important -- if both are false, we want to return falseC rather than a copy of falseC.
     *      if(param == falseC) goto returnThis;
     *      if(this == falseC) goto returnParamCopy;
     * 
     *      this.disjuncts.addAll(param.disjuncts);
     *  returnThis:
     *      return this;
     * 
     *  returnTrue:
     *      return trueC;
     *  
     *  returnParamCopy:
     *      return param.copy();
     * }
     */
    protected void addConstraintOrMethod() {
        List singleConstraint = new LinkedList();
        singleConstraint.add(constraint.getType());
        startMethod("or", singleConstraint, constraint.getType(), Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        Local paramLocal = getParamLocal(0, constraint.getType());
        Local trueC = getStaticFieldLocal(constraint, "trueC", constraint.getType());
        Local falseC = getStaticFieldLocal(constraint, "falseC", constraint.getType());
        
        Stmt labelReturnTrue = getNewLabel();
        Stmt labelReturnThis = getNewLabel();
        Stmt labelReturnParamCopy = getNewLabel();
        
        doJumpIfEqual(thisLocal, trueC, labelReturnTrue);
        doJumpIfEqual(paramLocal, trueC, labelReturnTrue);
        // order is important -- if both are false, we want to return falseC rather than a copy of falseC.
        doJumpIfEqual(paramLocal, falseC, labelReturnThis);
        doJumpIfEqual(thisLocal, falseC, labelReturnParamCopy);
        
        // if we're here -- we need to add paramLocal's disjuncts to this.disjuncts
        Local thisSet = getFieldLocal(thisLocal, "disjuncts", setType);
        Local paramSet = getFieldLocal(paramLocal, "disjuncts", setType);
        doMethodCall(thisSet, "addAll", singleCollectionType, BooleanType.v(), paramSet);

        // the jump labels:
        doAddLabel(labelReturnThis);
        doReturn(thisLocal);
        
        doAddLabel(labelReturnTrue);
        doReturn(trueC);
        
        doAddLabel(labelReturnParamCopy);
        doReturn(getMethodCallResult(paramLocal, "copy", constraint.getType()));
    }
    
    /**
     * Add a method with signature Constraint copy(Constraint arg);
     * 
     * That method returns a copy of arg. It reuses the same disjuncts, but constructs a new set to hold them, and
     * a new Constraint object with that new set as its disjuncts set.
     * 
     * public Constraint$tm copy() {
     *      return new Constraint$tm(new LinkedHashSet(this.disjuncts));
     * }
     */
    protected void addConstraintCopyMethod() {
        startMethod("copy", emptyList, constraint.getType(), Modifier.PUBLIC);
        
        List singleSet = new LinkedList();
        singleSet.add(setType);
        
        Local thisLocal = getThisLocal();
        
        // resultSet = new linkedHashSet(this.disjuncts);
        Local resultSet = getNewObject(setClass, singleCollectionType, getFieldLocal(thisLocal, "disjuncts", setType));
        
        // return new Constraint(resultSet);
        doReturn(getNewObject(constraint, singleSet, resultSet));
    }
    
    /**
     * Add a method with signature Object[] getDisjunctArray();
     * 
     *  Needed by the advice -- it is used to iterate over all solutions to a constraint. Simply uses Set.toArray().
     *  
     *  public Object[] getDisjunctArray() {
     *      return this.disjuncts.toArray();
     *  }
     */
    protected void addConstraintGetDisjunctArrayMethod() {
        Type arrayType = ArrayType.v(objectType, 1);
        startMethod("getDisjunctArray", emptyList, arrayType, Modifier.PUBLIC);
        
        // return this.disjuncts.toArray();
        doReturn(getMethodCallResult(getFieldLocal(getThisLocal(), "disjuncts", setType), "toArray", arrayType));
    }

    /**
     * Add methods with signature Constraint addBindingsForSymbolX(bindings..) for each symbol X of the tracematch.
     * 
     * The Constraint class will contain, for each tracematch symbol X, a method called
     * addBindingsForSymbolX(). The parameters for this method are two ints (the numbers of
     * the 'from' and 'to' states of the transition that is being taken at run-time), 
     * followed by an Object parameter for every tracematch variable bound by the symbol.
     * 
     * The idea is that these methods record new bindings obtained by following a transition in the NFA triggered
     * by an event in the program, and return falseC if the new bindings are incompatible with this constarint, 
     * or a new constraint that incorporates the new bindings if they *are* compatible.
     * 
     * For each tracematch symbol S0, with no variables:
     * public Constraint$tm addBindingsForSymbolS0(int from, int to) {
     *      return this;
     * }
     * 
     * For each tracematch symbol S with variables x1..xn:
     * public Constraint$tm addBindingsForSymbolS(int from, int to, Type1 x1, ..., Typen xn) {
     *      if(this == falseC) goto returnFalse;
     *      LinkedHashSet resultDisjuncts = new LinkedHashSet();
     *      Iterator disjunctsIt = this.disjuncts.iterator();
     *  loopBegin:
     *      if(!disjunctsIt.hasNext()) goto loopEnd;
     *      Disjunct$tm curDisjunct = (Disjunct$tm)it.next();
     *      if(curDisjunct.validateDisjunct(to)) goto disjunctValid;
     *      disjunctIt.remove();
     *      goto loopBegin;
     *  disjunctValid:
     *      resultDisjuncts.add(curDisjunct.addBindingsForSymbolS(from, to, x1, ..., xn);
     *      goto loopBegin;
     *  loopEnd:
     *      resultDisjuncts.remove(Disjunct$tm.falseD);
     *      if(resultDisjuncts.isEmpty()) goto returnFalse;
     *      return new Constraint$tm(resultDisjuncts);
     *  returnFalse:
     *      return falseC;
     *  }
     *  
     */
    protected void addConstraintAddBindingsMethods() {
        List singleInt = new LinkedList();
        singleInt.add(IntType.v());
        List singleSet = new LinkedList();
        singleSet.add(setType);
        
        Iterator symbolIt = curTraceMatch.getSymbols().iterator();
        String symbol;
        while(symbolIt.hasNext()) {
            symbol = (String) symbolIt.next();
            List variables = curTraceMatch.getVariableOrder(symbol);
            List parameterTypes = new LinkedList();
            parameterTypes.add(IntType.v()); // number of originating state of the transition
            parameterTypes.add(IntType.v()); // number of the target state of the transition
            int varCount = variables.size();
            for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                parameterTypes.add(curTraceMatch.bindingType((String)varIt.next()));
            }
            startMethod("addBindingsForSymbol" + symbol, parameterTypes, constraint.getType(), Modifier.PUBLIC);
            if(varCount == 0) {
                // if we have no variables, we just return this
                doReturn(getThisLocal());
            } else {
                Local thisLocal = getThisLocal();
                
                Local falseC = getStaticFieldLocal(constraint, "falseC", constraint.getType());
                
                // We'll need these labels..
                Stmt labelReturnFalse = getNewLabel();
                Stmt labelLoopBegin = getNewLabel();
                Stmt labelLoopEnd = getNewLabel();
                Stmt labelDisjunctValid = getNewLabel();
                
                // if(this == falseC) return false;
                doJumpIfEqual(thisLocal, falseC, labelReturnFalse);

                // Store all parameters in local variables, put those variables in a list
                List parameterLocals = new LinkedList();
                
                int parameterIndex = 0;
                Local stateFrom = getParamLocal(parameterIndex++, IntType.v());
                parameterLocals.add(stateFrom);
                
                Local stateTo = getParamLocal(parameterIndex++, IntType.v());
                parameterLocals.add(stateTo);
                
                for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                    parameterLocals.add(getParamLocal(parameterIndex++, curTraceMatch.bindingType((String)varIt.next())));
                }
                
                Local thisDisjuncts = getFieldLocal(thisLocal, "disjuncts", setType);
                // new disjunct set for the result, as we don't change things in-place
                Local resultDisjuncts = getNewObject(setClass);
                
                Local disjunctIterator = getMethodCallResult(thisDisjuncts, "iterator", iteratorType);
                
                // we have to emulate loops with jumps. This is meant to be
                // while(disjunctIterator.hasNext());
                doAddLabel(labelLoopBegin);
                doJumpIfFalse(getMethodCallResult(disjunctIterator, "hasNext", BooleanType.v()), labelLoopEnd);
                
                Local curDisjunct = getCastValue(getMethodCallResult(disjunctIterator, "next", objectType), disjunct.getType());
                
                ////////// Cleanup of invalid disjuncts -- if the current disjunct isn't valid,
                // just remove it from the disjunct set and continue with the next.
                // if(!curDisjunct.validateDisjunct(stateTo) { it.remove(); goto labelLoopBegin; }
                doJumpIfTrue(getMethodCallResult(curDisjunct, "validateDisjunct", singleInt, BooleanType.v(), stateTo), 
                        labelDisjunctValid);
                doMethodCall(disjunctIterator, "remove", VoidType.v());
                doJump(labelLoopBegin);
                
                doAddLabel(labelDisjunctValid);
                ////////// end cleanup code
                
                Local resultDisjunct = getMethodCallResult(curDisjunct, "addBindingsForSymbol" + symbol, 
                        parameterTypes, disjunct.getType(), parameterLocals);
                doMethodCall(resultDisjuncts, "add", singleObjectType, BooleanType.v(), resultDisjunct);
                
                doJump(labelLoopBegin);
                // end of loop
                
                doAddLabel(labelLoopEnd);
                
                // We remove the false disjunct, then, if the disjunct set is empty, we return the
                // false constraint falseC, otherwise we return a new constraint with the 
                // appropriate disjunct set.
                Local falseD = getStaticFieldLocal(disjunct, "falseD", disjunct.getType());
                doMethodCall(resultDisjuncts, "remove", singleObjectType, BooleanType.v(), falseD);
                doJumpIfTrue(getMethodCallResult(resultDisjuncts, "isEmpty", BooleanType.v()), labelReturnFalse);
                
                // non-empty result set -- construct new constraint and return it
                doReturn(getNewObject(constraint, singleSet, resultDisjuncts));
                
                doAddLabel(labelReturnFalse);
                doReturn(falseC);
            }
        }
    }
    
    /**
     * Add methods with signature Constraint addNegativeBindingsForSymbolX(bindings..) for each symbol X of the tracematch.
     * 
     * The Constraint class will contain, for each tracematch symbol X, a method called
     * addNegativeBindingsForSymbolX(). The parameters for this method are an int (the number of
     * the 'to' state of the transition that is being taken at run-time), 
     * followed by an Object parameter for every tracematch variable bound by the symbol.
     * 
     * The idea is that these methods record new bindings obtained by following a transition in the NFA triggered
     * by an event in the program, and return falseC if the new bindings are incompatible with this constarint, 
     * or a new constraint that incorporates the new bindings if they *are* compatible.
     * 
     * For each tracematch symbol S0 with no variables:
     * public Constraint$tm addNegativeBindingsForSymbolS0(int from) {
     *      return falseC;
     * }
     * 
     * For each tracematch symbol S with a single variable x:
     * public Constraint$tm addNegativeBindingsForSymbolS(int to, TypeX x) {
     *      if(this == falseC) goto returnFalse;
     *      Iterator disjunctsIt = this.disjuncts.iterator();
     *      LinkedHashSet resultSet = new LinkedHashSet();
     *  loopBegin:
     *      if(!disjunctsIt.hasNext()) goto loopEnd;
     *      Disjunct$tm curDisjunct = (Disjunct$tm) disjunctsIt.next();
     *      if(curDisjunct.validateDisjunct(to)) goto disjunctValid;
     *      disjunctIt.remove();
     *      goto loopBegin;
     *  disjunctValid:
     *      resultSet.add(curDisjunct.addNegativeBindingsForSymbolS(to, x);
     *      goto loopBegin;
     *  loopEnd:
     *      resultSet.remove(Disjunct$tm.falseD);
     *      if(resultSet.isEmpty()) goto returnFalse;
     *      return new Constraint$tm(resultSet);
     *  returnFalse:
     *      return falseC;
     *  }
     * For each tracematch symbol S with variables x1, ..., xn (n > 1):
     * public Constraint$tm addNegativeBindingsForSymbolS(int to, Type1 x1, ..., Typen xn) {
     *      if(this == falseC) goto returnFalse;
     *      Iterator disjunctsIt = this.disjuncts.iterator();
     *      LinkedHashSet resultSet = new LinkedHashSet();
     *  loopBegin:
     *      if(!disjunctsIt.hasNext()) goto loopEnd;
     *      Disjunct$tm curDisjunct = (Disjunct$tm) disjunctsIt.next();
     *      if(curDisjunct.validateDisjunct(to)) goto disjunctValid;
     *      disjunctIt.remove();
     *      goto loopBegin;
     *  disjunctValid:
     *      resultSet.addAll(curDisjunct.addNegativeBindingsForSymbolS(to, x1, ..., xn);
     *      goto loopBegin;
     *  loopEnd:
     *      resultSet.remove(Disjunct$tm.falseD);
     *      if(resultSet.isEmpty()) goto returnFalse;
     *      return new Constraint$tm(resultSet);
     *  returnFalse:
     *      return falseC;
     *  }
     *
     */
    protected void addConstraintAddNegativeBindingsMethods() {
        List singleInt = new LinkedList();
        singleInt.add(IntType.v());
        List singleSet = new LinkedList();
        singleSet.add(setType);
        
        Iterator symbolIt = curTraceMatch.getSymbols().iterator();
        String symbol;
        while(symbolIt.hasNext()) {
            symbol = (String) symbolIt.next();
            List variables = curTraceMatch.getVariableOrder(symbol);
            List parameterTypes = new LinkedList();
            parameterTypes.add(IntType.v()); // number of target state of the transition
            int varCount = variables.size();
            for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                parameterTypes.add(curTraceMatch.bindingType((String)varIt.next()));
            }
            startMethod("addNegativeBindingsForSymbol" + symbol, parameterTypes, constraint.getType(), Modifier.PUBLIC);
            if(varCount == 0) {
                // if we have no variables, we just return false
                doReturn(getStaticFieldLocal(constraint, "falseC", constraint.getType()));
            } else {
                Local thisLocal = getThisLocal();

                Local falseC = getStaticFieldLocal(constraint, "falseC", constraint.getType());
                // We'll need these labels..
                Stmt labelReturnFalse = getNewLabel();
                Stmt labelLoopBegin = getNewLabel();
                Stmt labelLoopEnd = getNewLabel();
                Stmt labelDisjunctValid = getNewLabel();
                
                // if(this == falseC) return false;
                doJumpIfEqual(thisLocal, falseC, labelReturnFalse);

                // Store all parameters in local variables, put those variables in a list
                List parameterLocals = new LinkedList();
                
                int parameterIndex = 0;
                Local stateTo = getParamLocal(parameterIndex++, IntType.v());
                parameterLocals.add(stateTo);
                
                for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                    parameterLocals.add(getParamLocal(parameterIndex++, curTraceMatch.bindingType((String)varIt.next())));
                }
                
                Local thisDisjuncts = getFieldLocal(thisLocal, "disjuncts", setType);
                // new disjunct set for the result, as we don't change things in-place
                Local resultDisjuncts = getNewObject(setClass);
                
                Local disjunctIterator = getMethodCallResult(thisDisjuncts, "iterator", iteratorType);
                
                // we have to emulate loops with jumps. This is meant to be
                // while(disjunctIterator.hasNext());
                doAddLabel(labelLoopBegin);
                doJumpIfFalse(getMethodCallResult(disjunctIterator, "hasNext", BooleanType.v()), labelLoopEnd);
                
                Local curDisjunct = getCastValue(getMethodCallResult(disjunctIterator, "next", objectType), disjunct.getType());
                
                ////////// Cleanup of invalid disjuncts -- if the current disjunct isn't valid,
                // just remove it from the disjunct set and continue with the next.
                // if(!curDisjunct.validateDisjunct(stateTo) { it.remove(); goto labelLoopBegin; }
                doJumpIfTrue(getMethodCallResult(curDisjunct, "validateDisjunct", singleInt, BooleanType.v(), stateTo), 
                        labelDisjunctValid);
                doMethodCall(disjunctIterator, "remove", VoidType.v());
                doJump(labelLoopBegin);
                
                doAddLabel(labelDisjunctValid);
                ////////// end cleanup code
                
                // addNegativeBindingsForSymbolX returns a Set if the symbol binds more than one variable,
                // and a single disjunct otherwise.
                if(varCount < 2) {
                    // resultDisjunct = addNegativeBindingsForSymbolX(stateTo, [bindings]);
                    Local resultDisjunct = getMethodCallResult(curDisjunct, "addNegativeBindingsForSymbol" + symbol, 
                            parameterTypes, disjunct.getType(), parameterLocals);
                    doMethodCall(resultDisjuncts, "add", singleObjectType, BooleanType.v(), resultDisjunct);
                } else {
                    // resultDisjunctSet = addNegativeBindingsForSymbolX(stateTo, [bindings]);
                    Local resultDisjunctSet = getMethodCallResult(curDisjunct, "addNegativeBindingsForSymbol" + symbol, 
                            parameterTypes, setType, parameterLocals);
                    doMethodCall(resultDisjuncts, "addAll", singleCollectionType, BooleanType.v(), resultDisjunctSet);
                }
                
                doJump(labelLoopBegin);
                // end of loop
                
                doAddLabel(labelLoopEnd);
                
                // We remove the false disjunct, then, if the disjunct set is empty, we return the
                // false constraint falseC, otherwise we return a new constraint with the 
                // appropriate disjunct set.
                Local falseD = getStaticFieldLocal(disjunct, "falseD", disjunct.getType());
                doMethodCall(resultDisjuncts, "remove", singleObjectType, BooleanType.v(), falseD);
                doJumpIfTrue(getMethodCallResult(resultDisjuncts, "isEmpty", BooleanType.v()), labelReturnFalse);
                
                // non-empty result set -- construct new constraint and return it
                doReturn(getNewObject(constraint, singleSet, resultDisjuncts));
                
                doAddLabel(labelReturnFalse);
                doReturn(falseC);
            }
		}
    }
    
    //////////////// Generation of the Disjunct class
    /**
     * Fills in the fields and methods of the disjunct class. A disjunct represents a (potentially incomplete)
     * solution of a constraint, i.e. a (potentially incomplete) set of bindings that would make the constraint
     * true. For every tracematch formal variable X, the Disjunct class has a field var$X which records the 
     * current binding of X a set not$X of negative bindings for X, and, for non-primitive types, a field 
     * weak$X containing the weak binding for X, if any. There is also a method get$X() which returns the value 
     * bound by X, whether X is weak or strong. 
     *
     * There is also a boolean field X$isBound which is true iff X is bound (weakly or strongly -- thus, for
     * reference types, X$isBound == true iff (var$X != null || weak$X != null). For primitive types, this flag
     * is needed, as otherwise it's impossible to determine whether a variable has been bound. It is used for
     * reference types as well to save some time -- checking for 'boundness' is "if(X$isBound)" rather than
     * "if(var$X == null && weak$X == null)", so potentially quicker. We could conceivably trade some space for
     * some time here. 
     * 
     * There are two static fields, trueD and falseD, representing the true and false disjunct respectively.
     * 
     * Here is an outline of what is generated, assuming the tracematch is called 'tm':
     * 
     * public class Disjunct$tm {
     *      public static Disjunct$tm trueD = new Disjunct$tm();
     *      public static Disjunct$tm falseD = new Disjunct$tm();
     * 
     *      // For each tracematch variable X of type TypeX
     *      TypeX var$X;
     *      boolean X$isBound;
     *      LinkedHashSet not$X;
     *      public Disjunct$tm addNegativeBindingsForVariableX(TypeX binding);
     *      public TypeX get$X();
     *      // If X is a reference type (and not primitive)
     *      MyWeakRef weak$X;
     *      
     *      public Disjunct$tm();
     *      public Disjunct$tm(Disjunct$tm arg);
     *      protected void finalize();
     *      
     *      // For each tracematch variable X
     *  
     *      // For each tracematch symbol S binding variables x1, ..., xn:
     *      public Disjunct$tm addBindingsForSymbolX(int from, int to, Type1 x1, ..., Typen xn);
     *      public Disjunct$tm addNegativeBindingsForSymbolX(int to, Type1 x1, ..., Typen xn);
     *
     *      public boolean equals();
     *      public int hashCode();
     *
     *      public boolean validateDisjunct(int to);
     * }
     */
    protected void fillInDisjunctClass() {
        startClass(disjunct);
        addDisjunctClassMembers();
        addDisjunctInitialiser();
        addDisjunctStaticInitialiser();
        addDisjunctFinalizeMethod();
        addDisjunctGetVarMethods();
        addDisjunctAddBindingsForSymbolMethods();
        if(!abc.main.Debug.v().noNegativeBindings) {
            addDisjunctAddNegBindingsForSymbolMethods();
            addDisjunctAddNegativeBindingsForVariableMethods();
        }
        addDisjunctEqualsMethod();
        addDisjunctHashCodeMethod();
        // addDisjunctCopyMethod(); -- replaced by copy constructor
        addDisjunctValidateDisjunctMethod();
    }
    
    protected void addDisjunctClassMembers() {
        // static singleton fields for true/false disjuncts
        SootField trueDisjunct= new SootField("trueD", disjunct.getType(), Modifier.PUBLIC | 
                Modifier.FINAL | Modifier.STATIC);
        disjunct.addField(trueDisjunct);
        SootField falseDisjunct = new SootField("falseD", disjunct.getType(), Modifier.PUBLIC |
                        Modifier.FINAL | Modifier.STATIC);
        disjunct.addField(falseDisjunct);
        
        // the remainin fields depend upon the tracematch and its variables. For each tracematch
        // formal X, X$isBound (do we have a binding for X?), not$X (which values X mustn't take 
        // -- a set, and null if X is bound) and var$X (the value X is bound to, or null if X is
        // not bound strongly (0 if X is primitive)). Also, for non-primitive bindings, there is
        // a field weak$X storing a possible weak binding to X.
        List varNames = curTraceMatch.getFormalNames();
        Iterator varIt = varNames.iterator();
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            SootField curField = new SootField("var$" + varName, curTraceMatch.bindingType(varName),
                    Modifier.PUBLIC);
            disjunct.addField(curField);
            if(!abc.main.Debug.v().noNegativeBindings) {
                curField = new SootField("not$" + varName, setType,
                        Modifier.PUBLIC);
                disjunct.addField(curField);
            }
            curField = new SootField(varName + "$isBound", BooleanType.v(), Modifier.PUBLIC);
            disjunct.addField(curField);
            if(!curTraceMatch.isPrimitive(varName)) {
                curField = new SootField("weak$" + varName, curTraceMatch.weakBindingClass(varName).getType(),
                        Modifier.PUBLIC);
                disjunct.addField(curField);
            }
        }
    }
    
    /**
     * Provide two constructors for Disjuncts -- a 'default' constructor and a 'copy' constructor, the second
     * taking a Disjunct as a parameter.
     * 
     * Also, if debugging is enabled, print the character 'D' to stdout whenever a disjunct is constructed.
     * 
     * public Disjunct$tm() {
     *      super();
     *      #for(each Tracematch formal variable X) {
     *          this.not$X = new LinkedHashSet();
     *      #}
     *      if(debug) System.out.println("D");
     * }
     * 
     * public Disjunct$tm(Disjunct$tm param) {
     *      super();
     *      #for(each Tracematch formal variable X) {
     *          curBinding = param.var$X;
     *          this.var$X = curBinding;
     *          curVarIsBound = param.X$isBound;
     *          this.X$isBound = curVarIsBound;
     *          #if(X is non-primitive) {
     *              curWeakBinding = param.weak$X;
     *              this.weak$X = curWeakBinding;
     *          #}
     *          if(curVarIsBound) goto skipNegBindingsSet;
     *          #if(X is primitive) {
     *              this.not$x = new LinkedHashSet(param.not$x);
     *          #} else {
     *              paramSet = param.not$x;
     *              Iterator bindingIt = paramSet.iterator();
     *  loopBegin:
     *              if(!bindingIt.hasNext()) goto loopEnd;
     *              if(((#WeakRef)bindingIt.next()).get() == null) goto loopBegin;
     *  loopEnd:
     *          #}
     *  skipNegBindingsSet:
     *      #}
     *      if(debug) System.out.println("D");
     * }
     */
    protected void addDisjunctInitialiser() {
        // no-argument constructor
        startMethod(SootMethod.constructorName, emptyList, VoidType.v(), Modifier.PUBLIC);

        Local thisLocal = getThisLocal();
        
        // call super() -- TODO - do we need to do this?
        doConstructorCall(thisLocal, objectClass);
        
        List varNames = null;
        Iterator varIt = null;
        
        if(!abc.main.Debug.v().noNegativeBindings) {
            // Initialise each negative bindings set to a new set.
            // TODO -- possible optimisation -- spot which variables are bound after every initial transition
            // and don't allocate negative bindings sets for those. Probably small effect, though.
            varNames = curTraceMatch.getFormalNames();
            varIt = varNames.iterator();
            while(varIt.hasNext()) {
                String varName = (String)varIt.next();
                doSetField(thisLocal, "not$" + varName, setType, getNewObject(setClass));
            }
        }
        
        // For debugging -- print a 'D' whenever a disjunct is constructed
        doPrintString("D");
        
        doReturnVoid();
        
        
        // Single-disjunct-argument copy constructor
        List singleDisjunct = new LinkedList();
        singleDisjunct.add(disjunct.getType());
        startMethod(SootMethod.constructorName, singleDisjunct, VoidType.v(), Modifier.PUBLIC);
        
        thisLocal = getThisLocal();
        Local paramLocal = getParamLocal(0, disjunct.getType());
        
        // call super() -- TODO - do we need to do this?
        doConstructorCall(thisLocal, objectClass);
        
        // Initialise each negative bindings set to a new set.
        // TODO -- possible optimisation -- spot which variables are bound after every initial transition
        // and don't allocate negative bindings sets for those. Probably small effect, though.
        varNames = curTraceMatch.getFormalNames();
        varIt = varNames.iterator();
        Local curBinding, curWeakBinding, curVarIsBound, curSet;
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            Stmt labelSkipNegBindingsSet = getNewLabel();
            curBinding = getFieldLocal(paramLocal, "var$" + varName, curTraceMatch.bindingType(varName));
            doSetField(thisLocal, "var$" + varName, curTraceMatch.bindingType(varName), curBinding);
            curVarIsBound = getFieldLocal(paramLocal, varName + "$isBound", BooleanType.v());
            doSetField(thisLocal, varName + "$isBound", BooleanType.v(), curVarIsBound);
            if(!curTraceMatch.isPrimitive(varName)) {
                curWeakBinding = getFieldLocal(paramLocal, "weak$" + varName, 
                        curTraceMatch.weakBindingClass(varName).getType());
                doSetField(thisLocal, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType(),
                        curWeakBinding);
            }

            doJumpIfTrue(curVarIsBound, labelSkipNegBindingsSet);
            
            if(!abc.main.Debug.v().noNegativeBindings) {
                if(curTraceMatch.isPrimitive(varName)) {
                    curSet = getNewObject(setClass, singleCollectionType, getFieldLocal(paramLocal, "not$" + varName, setType));
                } else {
                    // We only want non-invalidated weak bindings to be contained in the newly constructed
                    // negative bindings set
                    Stmt labelLoopBegin = getNewLabel();
                    Stmt labelLoopEnd = getNewLabel();
                    Stmt labelWeakRefExpired = getNewLabel();
                    curSet = getNewObject(setClass);
                    Local paramSet = getFieldLocal(paramLocal, "not$" + varName, setType);
                    Local bindingIt = getMethodCallResult(paramSet, "iterator", iteratorType);
                    
                    doAddLabel(labelLoopBegin);
                    doJumpIfFalse(getMethodCallResult(bindingIt, "hasNext", BooleanType.v()), labelLoopEnd);
                    Local curVariable = getMethodCallResult(bindingIt, "next", objectType);
                    doJumpIfNull(getMethodCallResult(getCastValue(curVariable, 
                            curTraceMatch.weakBindingClass(varName).getType()), "get", objectType), labelWeakRefExpired);
                    doMethodCall(curSet, "add", singleObjectType, BooleanType.v(), curVariable);
                    doJump(labelLoopBegin);
                    
                    // XXX may turn disjuncts equal that weren't equal before.
                    // PA: I *think* that the operations we care about still work fine on LinkedHashSet.
                    doAddLabel(labelWeakRefExpired);
                    doMethodCall(bindingIt, "remove", VoidType.v());
                    doJump(labelLoopBegin);
                    
                    doAddLabel(labelLoopEnd);
                }
                
                doSetField(thisLocal, "not$" + varName, setType, curSet);
            }
            
            doAddLabel(labelSkipNegBindingsSet);
        }
        
        // For debugging -- print a 'D' whenever a disjunct is constructed
        doPrintString("D");
        
        doReturnVoid();
    }
    
    /**
     * Initialise the two static fields, trueD and falseD.
     */
    protected void addDisjunctStaticInitialiser() {
        startMethod(SootMethod.staticInitializerName, emptyList, VoidType.v(), Modifier.PUBLIC);
        
        // Need to initialise static members -- trueD and falseD.
        doSetStaticField(disjunct, "trueD", disjunct.getType(), getNewObject(disjunct));
        doSetStaticField(disjunct, "falseD", disjunct.getType(), getNewObject(disjunct));
        doReturnVoid();
    }
    
    /**
     * If debug tracing is enabled, print 'd' to stdout whenever a disjunct is finalized.
     * 
     * protected void finalize() { 
     *      if(debug) System.out.println("d");
     * }
     */
    protected void addDisjunctFinalizeMethod() {
        startMethod("finalize", emptyList, VoidType.v(), Modifier.PROTECTED);
        // For debugging -- print a "d" for disjunct destruction
        doPrintString("d");
        doReturnVoid();
    }
    
    /**
     * Add a method of signature Disjunct addNegativeBindingsForVariableX(TypeX binding) for each formal
     * tracematch parameter X.
     * 
     * The method adds a negative binding for a single variable to the current disjunct. there are three cases:
     * 1. The variable is bound to the same value as the new binding -- return falseD.
     * 2. The variable is bound to a different value -- return this.copy() (no need to record new negative binding).
     * 3. The variable is not bound -- return this.copy().not$var.add(new MyWeakRef(binding)).
     * 
     * public Disjunct$tm AddNegativeBindingsForVariableX(TypeX binding) {
     *      if(!this.X$isBound) goto varNotBound;
     *      if(this.get$X() == binding) goto returnFalse;
     *      return new Disjunct$tm(this);
     * 
     *  varNotBound:
     *      Disjunct$tm result = new Disjunct$tm(this);
     *      result.not$.add(new #WeakRefClass(binding));
     *      return result;
     *  
     *  returnFalse:
     *      return falseD;
     *  }
     */
    protected void addDisjunctAddNegativeBindingsForVariableMethods() {
        List singleDisjunct = new LinkedList();
        singleDisjunct.add(disjunct.getType());
        List varNames = curTraceMatch.getFormalNames();
        Iterator varIt = varNames.iterator();
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            Type varType = curTraceMatch.bindingType(varName);
            startMethod("addNegativeBindingForVariable" + varName, getList(varType),
                    disjunct.getType(), Modifier.PUBLIC);

            Stmt labelReturnFalse = getNewLabel();
            Stmt labelVarNotBound = getNewLabel();
            
            Local thisLocal = getThisLocal();
            Local paramLocal = getParamLocal(0, varType);
            
            doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), 
                            labelVarNotBound);
            
            // variable bound -- return false if bound to same value
            doJumpIfEqual(getMethodCallResult(thisLocal, "get$" + varName, varType), paramLocal, labelReturnFalse);
            
            // bound to a different value -- return new Disjunct(this); (i.e. this.copy())
            doReturn(getNewObject(disjunct, singleDisjunct, thisLocal));
            
            // variable isn't bound -- return this.copy().not$var.add(new WeakRef(paramLocal))
            doAddLabel(labelVarNotBound);
            Local result = getNewObject(disjunct, singleDisjunct, thisLocal);
            Local targetSet = getFieldLocal(result, "not$" + varName, setType);
            Local weakRef = getWeakRef(paramLocal, varName);
            doMethodCall(targetSet, "add", singleObjectType, BooleanType.v(), weakRef);
            doReturn(result);
            
            doAddLabel(labelReturnFalse);
            doReturn(getStaticFieldLocal(disjunct, "falseD", disjunct.getType()));
        }
    }
    
    /**
     * Add a method Object get$X() for each formal tracematch variable X.
     * 
     * Returns the value X is bound to, dereferencing a weak reference if necessary. Throws a runtime
     * exception if X is not bound.
     * 
     * public TypeX get$x() {
     *      #if(X is primitive) {
     *          if(!x$isBound) goto throwException;
     *          return this.var$x;
     *      #} else {
     *          if(this.var$X == null) goto bindingIsWeak;
     *          return this.var$X;
     *  bindingIsWeak:
     *          if(this.weak$X == null) goto throwException;
     *          return (TypeX)this.weak$X.get();
     *      #}
     *  throwException:
     *      throw new RuntimeException("Attempt to get an unbound variable: " + varName);
     *  }
     */
    protected void addDisjunctGetVarMethods() {
        List varNames = curTraceMatch.getFormalNames();
        Iterator varIt = varNames.iterator();
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            startMethod("get$" + varName, emptyList, curTraceMatch.bindingType(varName), Modifier.PUBLIC);
            Stmt labelThrowException = getNewLabel();
            Stmt labelBindingIsWeak = getNewLabel();
            
            Local thisLocal = getThisLocal();
            Local var = getFieldLocal(thisLocal, "var$" + varName, curTraceMatch.bindingType(varName));
            
            if(curTraceMatch.isPrimitive(varName)) {
                doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), labelThrowException);
                doReturn(var);
            } else {
                doJumpIfNull(var, labelBindingIsWeak);
                // binding is strong -- just return it
                doReturn(var);
                
                // binding is weak -- return ((MyWeakRef)var).get();
                doAddLabel(labelBindingIsWeak);
                var = getFieldLocal(thisLocal, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType());
                doJumpIfNull(var, labelThrowException);
                doReturn(getCastValue(getMethodCallResult(var, "get", objectType), curTraceMatch.bindingType(varName)));
            }
            
            // attempt to get an unbound variable -- throw an exception
            doAddLabel(labelThrowException);
            doThrowException("Attempt to get an unbound variable: " + varName);
        }
    }
    
    /**
     * Add methods addBindingsForSymbolX(int from, int to, TypeX bindings..) for each tracematch symbol X.
     * 
     * This method should be called when a transition in the NFA is taken after being triggered by an event
     * in the observed program. It checks whether the new bindings are compatible with this disjunct, and 
     * returns falseD if they're not and a new disjunct which records any new information if they are.
     * 
     * #for(each tracematch symbol S binding variables x1, ..., xn)
     * public Disjunct$tm addBindingsForSymbolS(int from, int to, Type1 x1, ..., Typen xn) {
     *      #for(each variable X bound by S) {
     *          if(this.X$isBound == false) goto curVarNotBound;
     *          TypeX curThisVal = thisLocal.get$X();
     *          if(curThisVal != X) goto returnFalse;
     *          goto checkNextVar;
     *  curVarNotBound:
     *          if(this.not$X.contains(new #WeakRef(X))) goto returnFalse;
     *  checkNextVar:
     *      #}
     *      switch(to) {
     *          case N: // #for every state number N
     *              #if(state N guarantees all tracematch formals bound) {
     *                  switch(from) {
     *                      case M: // #for each state M with the same strong-ref behaviour as N
     *                          goto returnThis;
     *                  }
     *              #}
     *              result = new Disjunct$tm(this);
     *              #if(there are variables which may have changed) {
     *                  switch(from) {
     *                      case M: // #for each state M that has a transition that may change variable state
     *                          #for(each variable X that may change state in M->N) {
     *                              #if(X is not guaranteed bound by the previous state)
     *                                  result.X$isBound = true;
     *                              #if(X must be strong in N) {
     *                                  #if(X is not of primitive type)
     *                                      result.weak$X = null;
     *                                  result.var$X = X;
     *                              #} else {
     *                                  result.weak$X = new #WeakRef(X);
     *                              #}
     *                          #}
     *                          goto returnResult;
     *                  }
     *          }
     *  returnResult:
     *          return result;
     *      }
     *  }
     *      
     */
    protected void addDisjunctAddBindingsForSymbolMethods() {
        List singleDisjunct = new LinkedList();
        singleDisjunct.add(disjunct.getType());
        
        Iterator symbolIt = curTraceMatch.getSymbols().iterator();
        String symbol;
        while(symbolIt.hasNext()) {
            symbol = (String) symbolIt.next();
            List variables = curTraceMatch.getVariableOrder(symbol);
            List parameterTypes = new LinkedList();
            parameterTypes.add(IntType.v()); // number of originating state of the transition
            parameterTypes.add(IntType.v()); // number of the target state of the transition
            int varCount = variables.size();
            for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                parameterTypes.add(curTraceMatch.bindingType((String)varIt.next())); // one parameter for each bound variable
            }
            
            startMethod("addBindingsForSymbol" + symbol, parameterTypes, disjunct.getType(), Modifier.PUBLIC);
            Stmt labelReturnFalse = getNewLabel();
            Stmt labelReturnThis = getNewLabel();
            
            // the first part of this method just checks if the new bindings are compatible.
            Local thisLocal = getThisLocal();
            int parameterIndex = 0;
            Local stateFrom = getParamLocal(parameterIndex++, IntType.v());
            Local stateTo = getParamLocal(parameterIndex++, IntType.v());
            

            // we store all bindings in a list
            List/*<Local>*/ bindings = new LinkedList();
            Iterator varIt = variables.iterator();
            while(varIt.hasNext()) {
                bindings.add(getParamLocal(parameterIndex++, curTraceMatch.bindingType((String)varIt.next())));
            }
            
            Iterator bindIt = bindings.iterator();
            varIt = variables.iterator();
            while(bindIt.hasNext()) {
                String varName = (String)varIt.next();
                Local curVar = (Local)bindIt.next();
                Stmt labelCurVarNotBound = getNewLabel();
                Stmt labelCheckNextVar = getNewLabel();
                doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), labelCurVarNotBound);
                
                Local curThisVarVal = getMethodCallResult(thisLocal, "get$" + varName, curTraceMatch.bindingType(varName));
                // return false if already incompatible
                doJumpIfNotEqual(curThisVarVal, curVar, labelReturnFalse);
                // since the current variable is bound, we skip the check of the negative binding sets
                doJump(labelCheckNextVar);
                
                doAddLabel(labelCurVarNotBound);
                
                if(!abc.main.Debug.v().noNegativeBindings) {
                    // compare negative binding sets
                    // The check we actually do is if(this.not$var.contains(new #WeakRef(curVar))), since only
                    // weak bindings are stored in the negative bindings sets. This relies on #WeakRef.equals()
                    // returning true if and only if there is reference equality between the two referents.
                    // 
                    // For reference types, we use the MyWeakRef class for the runtime, for primitive types it's
                    // the corresponding boxed type.
                    doJumpIfTrue(getMethodCallResult(getFieldLocal(thisLocal, "not$" + varName, setType),
                            "contains", singleObjectType, BooleanType.v(), getWeakRef(curVar, varName)), 
                                    labelReturnFalse);
                }
                
                doAddLabel(labelCheckNextVar);
            }
            
            // OK, if we fall through here then this disjunct is compatible with the new bindings,
            // otherwise we would have jumped to labelReturnFalse. Now we have a lookup switch to
            // distinguish between different states and hence perform the required weak/strong
            // behaviour.
            
            // To construct the LookupSwitch, we need two lists of equal length -- a list of values
            // to compare to (those will be state numbers of states that have appropriate transitions)
            // and a list of labels to jump to.
            List switchValues = new LinkedList();
            List switchLabels = new LinkedList();
            List incomingNodes = new LinkedList();
            Iterator stateIt = ((TMStateMachine)curTraceMatch.getStateMachine()).getStateIterator();
            while(stateIt.hasNext()) {
                SMNode state = (SMNode)stateIt.next();
                if(state.hasInEdgeWithLabel(symbol)) {
                    switchValues.add(getInt(state.getNumber()));
                    switchLabels.add(getNewLabel());
                    incomingNodes.add(state);
                }
            }
            
            Stmt labelThrowException = getNewLabel();
            
            doLookupSwitch(stateTo, switchValues, switchLabels, labelThrowException);
            
            Iterator labelIt = switchLabels.iterator();
            stateIt = incomingNodes.iterator();
            while(labelIt.hasNext()) {
                Stmt curLabel = (Stmt)labelIt.next();
                SMNode curState = (SMNode)stateIt.next();
                
                doAddLabel(curLabel);
                
                // Optimisation: Only construct new disjuncts when necessary
                // =============
                // Observe that once a disjunct is fully bound, the only way in which it can change
                // further (since we don't keep track of negative bindings) is if it has to
                // strengthen a reference that was hitherto weak. Thus, if the current state is
                // guaranteed to have bound all tracematch variables, we want to return 'this' rather
                // than a new disjunct object if we are coming from a state that also has all variables
                // bound, and which agrees with the current state regarding needStrongRefs.
                if(curState.boundVars.equals(new LinkedHashSet(curTraceMatch.getFormalNames()))) {
                    // the current state binds all variables.
                    // Great -- construct a list of all states S such that there is a transition labelled
                    // with 'symbol' from S to curState, where S binds all variables and has the same
                    // needStrongRefs set as curState.
                    List skipStates = new LinkedList();
                    List jumpLabels = new LinkedList();
                    Stmt labelContinueNormally = getNewLabel();
                    Iterator incomingIt = curState.getInEdgeIterator();
                    while(incomingIt.hasNext()) {
                        SMEdge incoming = (SMEdge)incomingIt.next();
                        if(incoming.getLabel().equals(symbol)) {
                            SMNode predecessor = incoming.getSource();
                            if(predecessor.boundVars.equals(curState.boundVars) 
                                    && predecessor.needStrongRefs.equals(curState.needStrongRefs)) {
                                int index; // lookupswitch values list must be sorted
                                for(index = 0; index < skipStates.size(); index++) {
                                    if(((IntConstant)(skipStates.get(index))).value > predecessor.getNumber()) break;
                                }
                                skipStates.add(index, getInt(predecessor.getNumber()));
                                jumpLabels.add(labelReturnThis);
                            }
                        }
                    }
                    
                    // If there are any states that allow the optimisation, do it:
                    if(!skipStates.isEmpty()) {
                        doLookupSwitch(stateFrom, skipStates, jumpLabels, labelContinueNormally);
                        doAddLabel(labelContinueNormally);
                    }
                }

                // We only need to reassign those bindings who aren't already present.
                // Given predecessor state S (with number 'from'), we want to reassign variable X only if
                // (a) S doesn't bind X, or
                // (b) S binds X weakly, but the current state binds it strongly.
                // Thus, we keep, for each predecessor state S, a list of variables which need to be reassigned.
                Iterator nodeIt = ((TMStateMachine)curTraceMatch.getStateMachine()).getStateIterator();
                List predecessorStates = new LinkedList();
                Map varsForState = new HashMap();
                switchLabels = new LinkedList();
                switchValues = new LinkedList();
                // Assumption: the nodes are iterated in increasing numeric order (lookup switch labels must be sorted)
                while(nodeIt.hasNext()) {
                    SMNode pred = (SMNode)nodeIt.next();
                    if(pred.hasEdgeTo(curState, symbol)) {
                        // we're interested in variables bound by this symbol (the List 'variables') that are not guaranteed
                        // bound by the predecessor symbol, or that are bound weakly by the predecessor and strongly by
                        // the current state.
                        Iterator it = variables.iterator();
                        List varsToUpdate = new LinkedList();
                        while(it.hasNext()) {
                            String var = (String)it.next();
                            if(!pred.boundVars.contains(var) || 
                                    (!pred.needStrongRefs.contains(var) && curState.needStrongRefs.contains(var))) {
                                varsToUpdate.add(var);
                            }
                        }
                        if(!varsToUpdate.isEmpty()) {
                            predecessorStates.add(pred);
                            varsForState.put(pred, varsToUpdate);
                            switchLabels.add(getNewLabel());
                            switchValues.add(getInt(pred.getNumber()));
                        }
                    }
                }
                
                // varsForState now contains, for each potential predecessor state, the list of variables that could have 
                // changed and thus need re-binding in going from that state to the current one.
                // It is empty if and only if every predecessor state binds exactly the same variables as the current state.
                // If this arises, then we merely want to create a copy of the predecessor constraint.
                
                // We create a copy of this, add the new bindings and return it
                Local result = getNewObject(disjunct, singleDisjunct, thisLocal);
                
                if(!varsForState.keySet().isEmpty()) {
                    Stmt labelReturnResult = getNewLabel();
                    doLookupSwitch(stateFrom, switchValues, switchLabels, labelReturnResult);
                    
                    Iterator labelIterator = switchLabels.iterator();
                    Iterator predIterator = predecessorStates.iterator();
                    while(labelIterator.hasNext()) {
                        Stmt switchLabel = (Stmt)labelIterator.next();
                        SMNode predState = (SMNode)predIterator.next();
                        doAddLabel(switchLabel);
                        varIt = variables.iterator();
                        bindIt = bindings.iterator();
                        while(varIt.hasNext()) {
                            String varName = (String) varIt.next();
                            Local binding = (Local) bindIt.next();
                            if(((List)varsForState.get(predState)).contains(varName)) {
                                // Unless the variable is guaranteed bound by the predecessor state, we need to mark
                                // it as bound
                                if(!predState.boundVars.contains(varName))
                                    doSetField(result, varName + "$isBound", BooleanType.v(), getInt(1));
                                if(curState.needStrongRefs.contains(varName)) {
                                    if(!curTraceMatch.isPrimitive(varName))
                                        doSetField(result, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType(),
                                                getNull());
                                    doSetField(result, "var$" + varName, curTraceMatch.bindingType(varName), binding);
                                } else {
                                    doSetField(result, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType(), 
                                            getWeakRef(binding, varName));
                                }
                            }
                        }
                        doJump(labelReturnResult);
                    }
                    doAddLabel(labelReturnResult);
                    doReturn(result);
                }
                // new bindings are recorded -- return result;
                doReturn(result);
            }
            
            // unfinished business -- the labels
            doAddLabel(labelReturnFalse);
            doReturn(getStaticFieldLocal(disjunct, "falseD", disjunct.getType()));
            
            doAddLabel(labelReturnThis);
            // For debugging purposes -- print an 'x' when returning this, as it shows that the optimisation applies
            doPrintString("x");
            doReturn(thisLocal);
            
            doAddLabel(labelThrowException);
            doThrowException("Disjunct.addBindingsForSymbol" + symbol + " got an invalid state number: " + stateTo);
        }
    }
    
    /**
     * Add methods addNegativeBindingsForSymbolX(int to, Objects bindings..) for each tracematch symbol X.
     * 
     * Depending on how many variables are bound by this symbol, one of three things can happen:
     * - if no variables are bound, the result is falseD.
     * - if one variable is bound, the result is a single disjunct.
     * - if more than one variable is bound, the result is a set of disjuncts, since
     *    addNegBindings on a disjunct D is meant to return
     *           D && !(x1 == v1 && x2 == v2 && ...)
     *       <=> D && (x1 != v1 || x2 != v2 || ...)
     *       <=> (D && (x1 != v1)) || (D && (x2 != v2)) || ...
     *       
     * Because of this, the methods return a single disjunct for symbols that bind 0 or 1 variable, and a set
     * of disjuncts for other symbols. Note that all the elements of the set are easily computed by calling
     * addNegativeBindingsForVariableV().
     * 
     * For every tracematch symbol S0 binding no variables:
     * public Disjunct$tm addNegativeBindingsForSymbolS0(int to) {
     *      return this.falseD;
     * }
     * 
     * For every tracematch symbol S1 binding one variable X of type TypeX:
     * public Disjunct$tm addNegativeBindingsForSymbolS1(int to, TypeX x) {
     *      switch(to) {
     *          case N: // #for every state N with a skip loop that binds all variables -- TODO: can we weaken this?
     *              goto checkBindingsOnly;
     *          default:
     *              goto computeResultNormally;
     *      }
     *  computeResultNormally:
     *          result = this.addNegativeBindingsForVariableX(x);
     *          return result;
     *  checkBindingsOnly:
     *          if(this.get$X() != x) goto returnThis;
     *          return this.falseD;
     *  returnThis:
     *          return this;
     *  }
     *  
     *  For every tracematch symbol S binding two or more variables x1, ..., xn of types Type1, ..., Typen:
     *  public LinkedHashSet addNegativeBindingsForSymbolS(int to, Type1 x1, ..., Typen xn) {
     *      switch(to) {
     *          case N: // #for every state N with a skip loop that binds all variables -- TODO: can we weaken this?
     *              goto checkBindingsOnly;
     *          default:
     *              goto computeResultNormally;
     *      }
     *  computeResultNormally:
     *      resultSet = new LinkedHashSet();
     *      #for(each variable X bound by symbol S) {
     *          result = this.addNegativeBindingsForVariableX(x);
     *          resultSet.add(result);
     *      #}
     *      return resultSet;
     *  checkBindingsOnly:
     *      #for(each variable X bound by symbol S) {
     *          if(this.get$X() != x) goto returnThis;
     *      #}
     *      result = this.falseD;
     *      resultSet.add(result);
     *      return resultSet;;
     *  returnThis:
     *      resultSet.add(this);
     *      return resultSet;
     *  }
     */
    protected void addDisjunctAddNegBindingsForSymbolMethods() {
        List singleDisjunct = new LinkedList();
        singleDisjunct.add(disjunct.getType());
        
        Iterator symbolIt = curTraceMatch.getSymbols().iterator();
        String symbol;
        while(symbolIt.hasNext()) {
            symbol = (String) symbolIt.next();
            List variables = curTraceMatch.getVariableOrder(symbol);
            List parameterTypes = new LinkedList();
            parameterTypes.add(IntType.v()); // number of the target state of the transition
            int varCount = variables.size();
            for(Iterator it = variables.iterator(); it.hasNext(); ) {
                parameterTypes.add(curTraceMatch.bindingType((String)it.next())); // one parameter for each bound variable
            }
            
            boolean returnSet = (varCount > 1);
            Type returnType = (returnSet) ? setType : disjunct.getType();
            
            startMethod("addNegativeBindingsForSymbol" + symbol, parameterTypes, returnType, Modifier.PUBLIC);
            
            if(varCount == 0) {
                doReturn(getStaticFieldLocal(disjunct, "falseD", disjunct.getType()));
                continue;
            }
            
            Local thisLocal = getThisLocal();
            int parameterIndex = 0;
            Local stateTo = getParamLocal(parameterIndex++, IntType.v());
            
            // we store all bindings in a list
            List/*<Local>*/ bindings = new LinkedList();
            Iterator varIt = variables.iterator();
            while(varIt.hasNext()) {
                bindings.add(getParamLocal(parameterIndex++, curTraceMatch.bindingType((String)varIt.next())));
            }

            // We implement the following optimisation:
            // If we are currently in a state that is guaranteed to bind all variables, then the actual underlying
            // disjuncts will never be changed by addNegativeBindings -- all that can happen is that the bindings
            // are incompatible, in which case we return falseD, or that the bindings *are* compatible, in which
            // case we can return this rather than this.copy();
            // This relies on the fact that addNegativeBindings is only called on skip looks; thus the 'from' and
            // the 'to' states of the transition always have the same strong-references behaviour, since they're
            // the same node, and therefore we would never have to 'strengthen' previously weak bindings when
            // doing this.
            Stmt labelComputeResultNormally = Jimple.v().newNopStmt();
            Stmt labelCheckBindingsOnly = Jimple.v().newNopStmt();

            List jumpToLabels = new LinkedList(); // list of labels for the switch statement
            List jumpOnValues = new LinkedList(); // list of IntConstants for the switch statement
            
            Iterator stateIt = ((TMStateMachine)curTraceMatch.getStateMachine()).getStateIterator();
            while(stateIt.hasNext()) {
                SMNode curNode = (SMNode)stateIt.next();
                // if all variables are bound and the state has a skip loop -- we want to optimise it.
                if(curNode.hasEdgeTo(curNode, "") 
                        && curNode.boundVars.equals(new LinkedHashSet(curTraceMatch.getFormalNames()))) {
                    jumpToLabels.add(labelCheckBindingsOnly);
                    jumpOnValues.add(getInt(curNode.getNumber()));
                }
            }
            
            Local resultSet = (returnSet ? getNewObject(setClass) : null);
            Local result = null;

            // If we have found any states that allow the optimisation, then do it.
            if(!jumpToLabels.isEmpty()) {
                doLookupSwitch(stateTo, jumpOnValues, jumpToLabels, labelComputeResultNormally);
                doAddLabel(labelComputeResultNormally);
            }

            // "normal" result computation: call Disjunct.addNegativeBindingForVariable with each var/binding pair
            // and accumulate results in a set if needed, then return.
            varIt = variables.iterator();
            Iterator bindIt = bindings.iterator();
            while(varIt.hasNext()) {
                String varName = (String)varIt.next();
                Local binding = (Local)bindIt.next();
                
                result = getMethodCallResult(thisLocal, "addNegativeBindingForVariable" + varName,
                        getList(curTraceMatch.bindingType(varName)), disjunct.getType(), binding);
                
                if(returnSet) {
                    doMethodCall(resultSet, "add", singleObjectType, BooleanType.v(), result);
                }
            }
            
            if(returnSet) {
                doReturn(resultSet);
            } else {
                doReturn(result);
            }
            
            // if the optimisation above applied -- suppose the disjunct is fully bound. Adding negative bindings
            // won't change it. They'll either be incompatible (result falseD) or compatible (result this).
            if(!jumpToLabels.isEmpty()) {
                doAddLabel(labelCheckBindingsOnly);
                Stmt labelReturnThis = getNewLabel();
                varIt = variables.iterator();
                bindIt = bindings.iterator();
                while(varIt.hasNext()) {
                    String varName = (String)varIt.next();
                    Local binding = (Local)bindIt.next();
                    
                    // if there's even just one variable whose binding doesn't contradict the new set of bindings,
                    // then the resulting set of disjuncts would contain 'this', so we just return it.
                    doJumpIfNotEqual(getMethodCallResult(thisLocal, "get$" + varName, curTraceMatch.bindingType(varName)),
                            binding, labelReturnThis);
                }
                
                // if we fall through here, all bindings were incompatible, so we return false
                result = getStaticFieldLocal(disjunct, "falseD", disjunct.getType());
                if(returnSet) {
                    doMethodCall(resultSet, "add", singleObjectType, BooleanType.v(), result);
                    doReturn(resultSet);
                } else {
                    doReturn(result);
                }
                
                doAddLabel(labelReturnThis);
                if(returnSet) {
                    doMethodCall(resultSet, "add", singleObjectType, BooleanType.v(), thisLocal);
                    doReturn(resultSet);
                } else {
                    doReturn(thisLocal);
                }
            }
        }
    }
    
    /**
     * This method is very important, as the default Set implementations contain elements
     * unique with respect to the equals() method. Thus, with a sensible implementation of
     * this method, we don't need to worry about removing duplicate disjuncts from the
     * disjunct sets in the constraint() class.
     * 
     * Two disjuncts are considered equal iff either they are the same object or, for every
     * variable X, either both bind X to the same value or neither binds X, and the set of
     * negative bindings for X is the same.
     * 
     * public boolean equals(Object param) {
     *      if(this == param) goto returnTrue;
     *      if(!param instanceof Disjunct$tm) goto returnFalse;
     *      paramDisjunct = (Disjunct$tm) param;
     *      #for(each tracematch variable X of type TypeX) {
     *          if(!this.X$isBound) goto thisNotBound;
     *          if(!paramDisjunct.X$isBound) goto returnFalse;
     *          if(this.var$X != paramDisjunct.var$X) goto returnFalse;
     *          #if(X is not primitive) {
     *              if(this.weak$X == null) goto thisWeakNull;
     *              if(paramDisjunct.weak$X == null) goto returnFalse;
     *              if(!this.weak$X.equals(paramDisjunct.weak$X)) goto returnFalse;
     *              goto checkNextVar;
     *  thisWeakNull:
     *              if(paramDisjunct.weak$X != null) goto returnFalse;
     *              goto checkNextVar;
     *          #}
     *          goto checkNextVar;
     * 
     *  thisNotBound:
     *          if(paramDisjunct.X$isBound) goto returnFalse;
     *          if(!this.not$X.equals(paramDisjunct.not$X)) goto returnFalse;
     *  checkNextVar:
     *      #}
     *  returnTrue:
     *      return true;
     *  returnFalse:
     *      return false;
     *  }
     */
    protected void addDisjunctEqualsMethod() {
        startMethod("equals", singleObjectType, BooleanType.v(), Modifier.PUBLIC);
        
        Stmt labelReturnTrue = getNewLabel();
        Stmt labelReturnFalse = getNewLabel();
        
        Local thisLocal = getThisLocal();
        Local paramLocal = getParamLocal(0, objectType);
        
        // if objects are identical, return true
        doJumpIfEqual(thisLocal, paramLocal, labelReturnTrue);
        
        // if type is wrong, return false
        doJumpIfNotInstanceOf(paramLocal, disjunct.getType(), labelReturnFalse);
        
        // it's the right type -- cast it
        Local paramDisjunct = getCastValue(paramLocal, disjunct.getType());
        
        Iterator varIt = curTraceMatch.getFormalNames().iterator();
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            
            Stmt labelThisNotBound = getNewLabel();
            Stmt labelCheckNextVar = getNewLabel();

            doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), labelThisNotBound);
            // the variable is bound by this -- if it isn't bound by the disjunct, return false
            doJumpIfFalse(getFieldLocal(paramDisjunct, varName + "$isBound", BooleanType.v()), labelReturnFalse);
            // both disjuncts bind the variable -- check reference equality of the bindings
            doJumpIfNotEqual(getFieldLocal(thisLocal, "var$" + varName, curTraceMatch.bindingType(varName)),
                    getFieldLocal(paramDisjunct, "var$" + varName, curTraceMatch.bindingType(varName)),
                    labelReturnFalse);
            if(!curTraceMatch.isPrimitive(varName)) {
                Stmt labelThisWeakNull = getNewLabel();
                doJumpIfNull(getFieldLocal(thisLocal, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType()),
                        labelThisWeakNull);
                doJumpIfNull(getFieldLocal(paramDisjunct, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType()), 
                        labelReturnFalse);
                doJumpIfFalse(getMethodCallResult(
                        getFieldLocal(thisLocal, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType()),
                        "equals", singleObjectType, BooleanType.v(),
                                getFieldLocal(paramDisjunct, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType())),
                        labelReturnFalse);
                doJump(labelCheckNextVar);
                
                doAddLabel(labelThisWeakNull);
                doJumpIfNotNull(getFieldLocal(thisLocal, "weak$" + varName, curTraceMatch.weakBindingClass(varName).getType()),
                        labelReturnFalse);
                doJump(labelCheckNextVar);
            }
            doJump(labelCheckNextVar);
            
            doAddLabel(labelThisNotBound);
            // the variable is not bound by this -- if it's bound by the disjunct, return false
            doJumpIfTrue(getFieldLocal(paramDisjunct, varName + "$isBound", BooleanType.v()), labelReturnFalse);
            if(!abc.main.Debug.v().noNegativeBindings) {
                // we now have to check whether the negative binding sets agree:
                // if(!this.not$var.equals(paramDisjunct.not$var)) return false;
                doJumpIfFalse(getMethodCallResult(getFieldLocal(thisLocal, "not$" + varName, setType), "equals",
                        singleObjectType, BooleanType.v(), getFieldLocal(paramDisjunct, "not$" + varName, setType)),
                        labelReturnFalse);
            }
            
            doAddLabel(labelCheckNextVar);
        }
        
        // we have now checked all variables -- if we haven't branched off, return true
        doAddLabel(labelReturnTrue);
        doReturn(getInt(1));
        
        doAddLabel(labelReturnFalse);
        doReturn(getInt(0));
    }
    
    /**
     * This method is very important, as the default hashCode() method doesn't fulfill its contract
     * with the modified Disjunct.equals() method, and inconsistent hashCodes may bugger up the
     * behaviour of HashSets and other things relying on them.
     * 
     * A hash code for the disjunct is obtained by adding up the hash codes of all bound variables and
     * all negative bindings sets for unbound variables. Note that for strong bindings, 
     * System.identityHashCode() is used, while for weak bindings weakBinding.hashCode(), which 
     * delegates (for the MyWeakRef implementation, at least) to System.identityHashCode() for its
     * referent.
     * 
     * public int hashCode() {
     *      int hashCode = 0;
     *      #for(each tracematch variable X) {
     *          if(!this.X$isBound) goto varUnbound;
     *          curVar = this.var$X;
     *          #if(X is primitive) {
     *              hashCode += (int)this.var$X;
     *              goto handleNextVar;
     *          #} else {
     *              if(curVar == null) goto varIsWeak;
     *              hashCode += java.lang.System.identityHashCode(curVar);
     *              goto handleNextVar;
     *  varIsWeak:
     *              hashCode += this.weak$X.hashCode();
     *              goto handleNextVar;
     *          #}
     *  varUnbound:
     *  handleNextVar:
     *      #}
     *      return hashCode;
     *  }
     */
    protected void addDisjunctHashCodeMethod() {
        startMethod("hashCode", emptyList, IntType.v(), Modifier.PUBLIC);
        Local thisLocal = getThisLocal();
        Local hashCode = getNewLocal(IntType.v(), getInt(0), "hashCode");
        
        Iterator varIt = curTraceMatch.getFormalNames().iterator();
        while(varIt.hasNext()) {
            String varName = (String)varIt.next();
            Stmt labelVarUnbound = getNewLabel();
            Stmt labelVarIsWeak = getNewLabel();
            Stmt labelHandleNextVar = getNewLabel();
            doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), labelVarUnbound);
            Local curVar = getFieldLocal(thisLocal, "var$" + varName, curTraceMatch.bindingType(varName));
            if(curTraceMatch.isPrimitive(varName)) {
                doAddToLocal(hashCode, getCastValue(curVar, IntType.v()));
                doJump(labelHandleNextVar);
            } else {
                doJumpIfNull(curVar, labelVarIsWeak);
                doAddToLocal(hashCode, getStaticMethodCallResult(Scene.v().getSootClass("java.lang.System"), 
                        "identityHashCode", singleObjectType, IntType.v(), curVar));
                doJump(labelHandleNextVar);
                
                doAddLabel(labelVarIsWeak);
                doAddToLocal(hashCode, getMethodCallResult(getFieldLocal(thisLocal, "weak$" + varName, 
                        curTraceMatch.weakBindingClass(varName).getType()), "hashCode", IntType.v()));
                doJump(labelHandleNextVar);
            }
            
            doAddLabel(labelVarUnbound);
            // We have a choice here: We can either make the hashcode depend on the negative bindings set, or we
            // can ignore it. If we ignore it, then disjuncts with identical positive bindings but different neg 
            // binding sets would have the same hashcode, and so would have to all be checked on lookups. If we
            // don't ignore it, then the computation of hashCode becomes expensive -- proportional to the number
            // of negative bindings.
            //
            // For now ignore negative bindings, but TODO consider this more carefully.
            //doAddToLocal(hashCode, getMethodCallResult(getFieldLocal(thisLocal, "not$" + varName, setType), 
            //        "hashCode", IntType.v()));
            
            doAddLabel(labelHandleNextVar);
        }
        
        doReturn(hashCode);
    }
    
    /**
     * Adds a method with the signature "public boolean validateDisjunct(int state);".
     * The idea is that the return value is false if one of the collectableWeakRefs of
     * the disjuct has expired, and true otherwise. Validating a disjunct before calling 
     * add[Neg]Bindings on it will enable clean-up of unneeded disjuncts 
     * 
     * public boolean validateDisjunct(int state) {
     *      switch(state) {
     *          case N: // #for each state N which has at least one collectableWeakRef
     *              #for(each collectibleWeakRef variable x Of state N) {
     *                  if(!this.x$isBound) goto curVarNotBound;
     *                  if(this.weak$x.get() == null) goto returnFalse;
     *  curVarNotBound:
     *      #}
     *  labelReturnTrue:
     *      return true;
     *  labelReturnFalse:
     *      #if(debug) System.out.println("*");
     *      return false;
     *  }
     */
    protected void addDisjunctValidateDisjunctMethod() {
        List singleInt = new LinkedList();
        singleInt.add(IntType.v());
        startMethod("validateDisjunct", singleInt, BooleanType.v(), Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        Local stateTo = getParamLocal(0, IntType.v());

        Stmt labelReturnTrue = getNewLabel();
        Stmt labelReturnFalse = getNewLabel();
        Stmt labelThrowException = getNewLabel();
        
        // We distinguish which state we're in by doing a lookup switch
        List switchValues = new LinkedList();
        List switchLabels = new LinkedList();
        List affectedStates = new LinkedList();
        Iterator stateIt = ((TMStateMachine)curTraceMatch.getStateMachine()).getStateIterator();
        while(stateIt.hasNext()) {
            SMNode state = (SMNode)stateIt.next();
            // States which have no collectableWeakRefs are always valid.
            if(!state.collectableWeakRefs.isEmpty()) {
                switchValues.add(getInt(state.getNumber()));
                switchLabels.add(getNewLabel());
                affectedStates.add(state);
            }
        }
        
        if(!switchValues.isEmpty())
            doLookupSwitch(stateTo, switchValues, switchLabels, labelReturnTrue);
        
        Iterator labelIt = switchLabels.iterator();
        stateIt = affectedStates.iterator();
        while(stateIt.hasNext()) {
            SMNode state = (SMNode)stateIt.next();
            Stmt label = (Stmt)labelIt.next();
            
            doAddLabel(label);
            //////// Cleaning up invalidated collectableWeakRefs
            // Each state is labelled with a set collectableWeakRefs. If one of these
            // becomes invalid, the disjunct can never lead to a successful match and,
            // accordingly, can/must be discarded.
            //
            // Since we don't allow null bindings and WeakRefs become null when they are
            // invalidated, we check each bound variable in the collectableWeakRefs set
            // for the current state for null-ness, and if it's null return false.
            Iterator collectableWeakRefIt = state.collectableWeakRefs.iterator();
            while(collectableWeakRefIt.hasNext()) {
                String varName = (String)collectableWeakRefIt.next();
                Stmt labelCurVarNotBound = getNewLabel();
                
                doJumpIfFalse(getFieldLocal(thisLocal, varName + "$isBound", BooleanType.v()), labelCurVarNotBound);
                
                // variable is bound -- check it
                doJumpIfNull(getMethodCallResult(getFieldLocal(thisLocal, "weak$" + varName, 
                                curTraceMatch.weakBindingClass(varName).getType()), "get", objectType), 
                        labelReturnFalse);
                
                doAddLabel(labelCurVarNotBound);
            }
            doJump(labelReturnTrue);
        }
        // if we don't branch out, the disjunct is valid
        doAddLabel(labelReturnTrue);
        doReturn(getInt(1));
        
        doAddLabel(labelReturnFalse);
        // Debug -- print out "*" for this kind of cleanup
        doPrintString("*");
        doReturn(getInt(0));
        
        doAddLabel(labelThrowException);
        doThrowException("Disjunct.validateDisjunct() called with an invalid state number");

    }
    
    
    
    /**
     * Methods to fill in the constraint class if indexing is to be used.
     */

    /**
     * Fills in the class members of the constraint class, i.e. three LinkedHashSets, three
     * Maps and one integer (to record which state we're on).
     */
    protected void addIndConstraintClassMembers() {
        SootField disjuncts = new SootField("disjuncts", setType, Modifier.PUBLIC);
        SootField disjuncts_tmp = new SootField("disjuncts_tmp", setType, Modifier.PUBLIC);
        SootField disjuncts_skip = new SootField("disjuncts_skip", setType, Modifier.PUBLIC);
        SootField indDisjuncts = new SootField("indexedDisjuncts", mapType, Modifier.PUBLIC);
        SootField indDisjuncts_tmp = new SootField("indexedDisjuncts_tmp", mapType, Modifier.PUBLIC);
        SootField indDisjuncts_skip = new SootField("indexedDisjuncts_skip", mapType, Modifier.PUBLIC);
        SootField onState = new SootField("onState", IntType.v(), Modifier.PUBLIC);
        
        // The indexing variables are in the following order:
        // [collectable] ++ [primitive] ++ [weak] ++ [strong].
        // The fields collectableUntil, primitiveUntil, weakUntil store the depth of the first variable
        // after the collectable, primitive and weak indices respectively. They are used for
        // determining what kind of map should be constructed: If the depth is less than
        // collectableUntil, a collecting weak identity map. Else, if the depth is less than primitiveUntil,
        // a java.util.HashMap. Else, if the depth is less than weakUntil, a weak identity map. Else,
        // an identity map.
        SootField collectableUntil = new SootField("collectableUntil", IntType.v(), Modifier.PUBLIC);
        SootField primitiveUntil = new SootField("primitiveUntil", IntType.v(), Modifier.PUBLIC);
        SootField weakUntil = new SootField("weakUntil", IntType.v(), Modifier.PUBLIC);
        
        constraint.addField(disjuncts);
        constraint.addField(disjuncts_tmp);
        constraint.addField(disjuncts_skip);
        constraint.addField(indDisjuncts);
        constraint.addField(indDisjuncts_tmp);
        constraint.addField(indDisjuncts_skip);
        constraint.addField(onState);
        constraint.addField(collectableUntil);
        constraint.addField(primitiveUntil);
        constraint.addField(weakUntil);
    }
    
    /**
     * Fills in the constraint constructor(s).
     */
    protected void addIndConstraintInitialiser() {
        
        // Single-int argument constructor -- the int is meant to be the state this constraint is for.
        List args = new LinkedList();
        args.add(IntType.v());
        startMethod(SootMethod.constructorName, args, VoidType.v(), Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        
        // call super()
        doConstructorCall(thisLocal, objectClass);
        
        // For debugging -- print a "C" for constraint construction
        doPrintString("C");

        // record which state we're on
        Local state = getParamLocal(0, IntType.v());
        doSetField(thisLocal, "onState", IntType.v(), state);
    
        // Each state machine node state stores the number of index variables of different types of
        // indexing variables. These are used to initialise the [collectable|primitive|weak]Until fields
        // of the associated constraint.
        // Thus, we do a lookup switch. Collect all the states in which we would partition the 
        // disjuncts, and treat everything else as the 'default:' clause.
        
        Stmt  labelDefault = getNewLabel();
        Map nodeToLabel = addStateLookupSwitch(state, labelDefault, true);
        
        for(Iterator nodeIt = nodeToLabel.keySet().iterator(); nodeIt.hasNext(); ) {
            SMNode node = (SMNode)nodeIt.next();
            doAddLabel((Stmt)nodeToLabel.get(node));
            
            if(node.nCollectable > 0) {
            	// first-level index variable is a collectable weakref
            	doSetField(thisLocal, "indexedDisjuncts", mapType, getNewCollWeakIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_tmp", mapType, getNewCollWeakIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_skip", mapType, getNewCollWeakIdMap());
            } else if(node.nPrimitive > 0) {
            	// first-level index variable is a primitive binding
                doSetField(thisLocal, "indexedDisjuncts", mapType, getNewHashMap());
                doSetField(thisLocal, "indexedDisjuncts_tmp", mapType, getNewHashMap());
                doSetField(thisLocal, "indexedDisjuncts_skip", mapType, getNewHashMap());
            } else if(node.nWeak > 0) {
            	// first-level index variable is a non-collectable weakref
            	doSetField(thisLocal, "indexedDisjuncts", mapType, getNewWeakIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_tmp", mapType, getNewWeakIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_skip", mapType, getNewWeakIdMap());
            } else {
            	// first-level index variable is a strong ref
            	doSetField(thisLocal, "indexedDisjuncts", mapType, getNewIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_tmp", mapType, getNewIdMap());
            	doSetField(thisLocal, "indexedDisjuncts_skip", mapType, getNewIdMap());
            }

            doSetField(thisLocal, "collectableUntil", IntType.v(), getInt(node.nCollectable));
            doSetField(thisLocal, "primitiveUntil", IntType.v(), getInt(node.nCollectable + node.nPrimitive));
            doSetField(thisLocal, "weakUntil", IntType.v(), getInt(node.nCollectable + node.nPrimitive + node.nWeak));
            
            doReturnVoid();
        }
        
        // In any case, we will have to provide the 'default' behaviour of initialising the sets
        doAddLabel(labelDefault);
        doSetField(thisLocal, "disjuncts", setType, getNewObject(setClass));
        doSetField(thisLocal, "disjuncts_tmp", setType, getNewObject(setClass));
        doSetField(thisLocal, "disjuncts_skip", setType, getNewObject(setClass));
        doSetField(thisLocal, "collectableUntil", IntType.v(), getInt(-1));
        doSetField(thisLocal, "primitiveUntil", IntType.v(), getInt(-1));
        doSetField(thisLocal, "weakUntil", IntType.v(), getInt(-1));
        
        doReturnVoid();
        
        // Constructor taking an int and a set. The int is the state it's on, the set is the initial set of
        // disjuncts. I assume this implies the constructor will only be called on states that don't partition:
        // TODO XXX Check this.

        // args still contains the single int from above
        args.add(setType);
        startMethod(SootMethod.constructorName, args, VoidType.v(), Modifier.PUBLIC);
        
        thisLocal = getThisLocal();

        // call super()
        doConstructorCall(thisLocal, objectClass);

        // For debugging -- print a "C" for constraint construction
        doPrintString("C");

        // record which state we're on
        state = getParamLocal(0, IntType.v());
        Local paramDisjuncts = getParamLocal(1, setType);
        doSetField(thisLocal, "onState", IntType.v(), state);
    
        // We assume this is a non-partitioning state, so set indexing helper fields = -1;
        doSetField(thisLocal, "collectableUntil", IntType.v(), getInt(-1));
        doSetField(thisLocal, "primitiveUntil", IntType.v(), getInt(-1));
        doSetField(thisLocal, "weakUntil", IntType.v(), getInt(-1));
        
        doSetField(thisLocal, "disjuncts", setType, paramDisjuncts);
        doSetField(thisLocal, "disjuncts_tmp", setType, getNewObject(setClass));
        
        // we initialise this.disjuncts_skip to new LinkedHashSet(this.disjuncts);
        args.clear(); args.add(Scene.v().getRefType("java.util.Collection"));
        doSetField(thisLocal, "disjuncts_skip", setType, getNewObject(setClass, args, paramDisjuncts));
        
        doReturnVoid();
    }

    /**
     * Fills in the constraint static initialiser.
     */
    protected void addIndConstraintStaticInitialiser() {
        // don't think there's anything to do..
    }
    
    /**
     * Fills in the constraint finalize() method.
     */
    protected void addIndConstraintFinalizeMethod() {
        startMethod("finalize", emptyList, VoidType.v(), Modifier.PROTECTED);
        // For debugging -- print a "c" for constraint destruction
        doPrintString("c");
        doReturnVoid();
    }
    
    /**
     * Adds a static method that constructs a constraint representing 'true', i.e. a constraint
     * whose Set of disjuncts contains a single disjunct.
     *
     * The generated method should look like:
     *     public static Constraint$tm getTrue(int state) {
     *         LinkedHashSet lhs = new LinkedHashSet();
     *         lhs.add(new Disjunct$tm());
     *         return new Constraint$tm(state, lhs);
     *     }
     */
    protected void addIndConstraintGetTrueMethod() {
        // the getTrue method takes a single int parameter
        List singleInt = new ArrayList(1);
        singleInt.add(IntType.v());

        // the constraint constructor takes an int and a set
        List int_and_set = new ArrayList(2);
        int_and_set.add(IntType.v());
        int_and_set.add(setType);

        startMethod("getTrue", singleInt, constraint.getType(), Modifier.STATIC | Modifier.PUBLIC);

        // create a new linked hashset and a new disjunct
        Local lhs = getNewObject(setClass);
        //Local new_disjunct = getNewObject(disjunct);
        Local new_disjunct = getStaticFieldLocal(disjunct, "trueD", disjunct.getType());

        // add the disjunct to the hashset
        doMethodCall(lhs, "add", singleObjectType, BooleanType.v(), new_disjunct);

        // create and return a new constraint
        List args = new ArrayList(2);
        args.add(getParamLocal(0, IntType.v()));
        args.add(lhs);
        doReturn(getNewObject(constraint, int_and_set, args));
    }
    
    /**
     * Adds 'merge' method which combines the _skip and _tmp-labelled constraints and prepares the
     * constraint for the next event.
     * 
     * The actual implementation uses two methods called 'merge'. One takes no arguments, while the
     * other takes a Map and a boolean operation flag. For descriptions, see comments in this method.
     */
    protected void addIndConstraintMergeMethod() {
        // First version of merge checks if the current state is indexed. If not, disjuncts{"", "_tmp",
        // "-skip"} are updated. Otherwise the indexedDisjunct* maps are updated, making use of the 
        // other auxiliary merge() method.
        startMethod("merge", emptyList, VoidType.v(), Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        Local collectableUntil = getFieldLocal(thisLocal, "collectableUntil", IntType.v());
        Stmt labelMergeIndices = getNewLabel();
        
        // If the current state uses indexing, we skip the simple case.
        doJumpIfGreater(collectableUntil, getInt(-1), labelMergeIndices);
        
        // Otherwise, the current state doesn't use indexing, so we merely update the disjunct sets.
        doSetField(thisLocal, "disjuncts", setType, getFieldLocal(thisLocal, "disjuncts_skip", setType));
        doMethodCall(getFieldLocal(thisLocal, "disjuncts", setType), "addAll", singleCollectionType, BooleanType.v(),
                    getFieldLocal(thisLocal, "disjuncts_tmp", setType));
        doSetField(thisLocal, "disjuncts_tmp", setType, getNewObject(setClass));
        doReturnVoid();
        
        // If the current state uses indexing -- we have to merge the maps.
        doAddLabel(labelMergeIndices);
        List formals = new LinkedList(), actuals = new LinkedList();
        formals.add(mapType);
        formals.add(BooleanType.v());
        actuals.add(getFieldLocal(thisLocal, "indexedDisjuncts_skip", mapType));
        actuals.add(getInt(1));
        doMethodCall(thisLocal, "merge", formals, VoidType.v(), actuals);
        actuals.clear();
        actuals.add(getFieldLocal(thisLocal, "indexedDisjuncts_tmp", mapType));
        actuals.add(getInt(0));
        doMethodCall(thisLocal, "merge", formals, VoidType.v(), actuals);
        
        doSetField(thisLocal, "indexedDisjuncts_tmp", mapType, 
                getNewMapForDepth(thisLocal, getInt(0)));
        doSetField(thisLocal, "indexedDisjuncts_skip", mapType, 
                getNewMapForDepth(thisLocal, getInt(0)));
        doReturnVoid();
        
        // The second version of merge takes a Map and a boolean flag. It merges the contents of the map
        // onto this.indexedDisjuncts. If the flag is true, it replaces existing disjunct sets; otherwise,
        // it computes set unions.
        // Note that the local 'formals' still contains the correct list of types from above.
        startMethod("merge", formals, VoidType.v(), Modifier.PUBLIC);
        
        thisLocal = getThisLocal();
        Local paramMap = getParamLocal(0, mapType);
        Local operation = getParamLocal(1, BooleanType.v());
        
        // The assumption is that (a) this method never gets called when we're not on a state that uses
        // indexing, and (b) the map it gets passed has the same structure as this.indexedDisjuncts (with
        // respect to order of indexing variables).
        // We iterate over paramMap rather than this.indexedDisjuncts, assuming that it will in general 
        // be smaller.
        
        Stmt labelNoIndices = getNewLabel();
        Map keyToLabel = addIndLookupSwitch(
                getFieldLocal(thisLocal, "onState", IntType.v()), 
                labelNoIndices, false);
        
        for(Iterator keyIt = keyToLabel.keySet().iterator(); keyIt.hasNext(); ) {
            Integer key = (Integer)keyIt.next();
            int depth = key.intValue();

            doAddLabel((Stmt)keyToLabel.get(key));
            
            // All this happens for every LinkedHashSet contained in the mapping structure:
            IterationContext context = new IterationContext(depth, paramMap);
            startIteration(context);
            Local mergedSet = getRelevantSet(context);
            
            // We have finally reached a set in the mapping structure.
            // We need to do `updated = lookup_depth(indexedDisjuncts, key[0], ..., key[depth-1]);`
            List formalsLookup= new LinkedList();
            List actualsLookup= new LinkedList();
            formalsLookup.add(mapType);
            actualsLookup.add(getFieldLocal(thisLocal, "indexedDisjuncts", mapType));
            for(int j = 0; j < depth; j++) {
                formalsLookup.add(objectType);
                actualsLookup.add(context.keys[j]);
            }
            
            Stmt labelThen = getNewLabel(), labelEndIf = getNewLabel();
            // if(op || updated == null) 
            doJumpIfTrue(operation, labelThen);

            Local updatedSet = getMethodCallResult(thisLocal, "lookup" + depth,
                    formalsLookup, setType, actualsLookup);
            doJumpIfEqual(updatedSet, getNull(), labelThen);
            
            // The 'else' branch:
            // updatedSet.addAll(mergedSet)
            doMethodCall(updatedSet, "addAll", singleCollectionType, BooleanType.v(), mergedSet);
            doJump(labelEndIf);
            
            // The 'then' branch:
            // overwrite_depth(indexedDisjuncts, keys[0], ..., keys[depth], mergedSet, true);
            doAddLabel(labelThen);
            List formalsOverwrite = new LinkedList();
            List actualsOverwrite = new LinkedList();
            formalsOverwrite.add(mapType);
            actualsOverwrite.add(getFieldLocal(thisLocal, "indexedDisjuncts", mapType));
            for(int j = 0; j < depth; j++) {
                formalsOverwrite.add(objectType);
                actualsOverwrite.add(context.keys[j]);
            }
            formalsOverwrite.add(setType);
            actualsOverwrite.add(mergedSet);
            formalsOverwrite.add(BooleanType.v());
            actualsOverwrite.add(getInt(1));
            doMethodCall(thisLocal, "overwrite" + depth, formalsOverwrite, VoidType.v(),
                    actualsOverwrite);
            
            // endif
            doAddLabel(labelEndIf);

            // No cleanup is needed on this iteration since overwrite() does a cleanup already.
            endIteration(context, false);
            
            doReturnVoid();
        }
        
        doAddLabel(labelNoIndices);
        doThrowException("merge(Map, boolean) called on a non-indexing state");
    }
    
    /**
     * Adds a method for obtaining an array of the disjuncts on this state.
     * We assume that final states are never indexed, so the disjuncts forming the solution
     * are simply in this.disjuncts.
     */
    protected void addIndConstraintGetDisjunctArrayMethod() {
        Type arrayType = ArrayType.v(objectType, 1);
        startMethod("getDisjunctArray", emptyList, arrayType, Modifier.PUBLIC);
        
        // return this.disjuncts.toArray();
        doReturn(getMethodCallResult(getFieldLocal(getThisLocal(), "disjuncts", setType), "toArray", arrayType));
    }
    
    /**
     * The 'addBindingsForSymbol' methods in the indexed case are called 'getBindingsForSymbol', since
     * they return a LinkedHashSet of the changed bindings.
     * 
     * The general plan is to determine the 'relevant' LinkedHashSets of disjuncts and iterate over them,
     * calling addBindingsForSymbol on each disjunct, and storing the results in a set which is ultimately
     * returned.
     */
    protected void addIndConstraintGetBindingsMethods() {
        Iterator symbolIt = curTraceMatch.getSymbols().iterator();
        while(symbolIt.hasNext()) {
            String symbol = (String)symbolIt.next();
            addIndConstraintGetBindingsForSymbolMethod(symbol);
        }
    }
    
    /**
     * Adds a getBindingsForSymbol method for the specified symbol
     * @param symbol
     */
    protected void addIndConstraintGetBindingsForSymbolMethod(String symbol) {
        List variables = curTraceMatch.getVariableOrder(symbol);
        List methodFormals = new LinkedList();
        methodFormals.add(IntType.v()); // number of the target state of the current transition
        int varCount = variables.size();
        for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
            methodFormals.add(curTraceMatch.bindingType((String)varIt.next()));
        }
        startMethod("getBindingsForSymbol" + symbol, methodFormals, setType, Modifier.PUBLIC);
        
        Local thisLocal = getThisLocal();
        Local onState = getFieldLocal(thisLocal, "onState", IntType.v());
        methodFormals.add(0, IntType.v()); // for calling Disjunct.addBindings...
        
        if(varCount == 0) {
            // if the symbol binds no variables, we simply return the contents of our current
            // disjunct set. We may still be indexing, however..

        		Stmt labelNoIndexing = getNewLabel();
            
            	Map depthToLabel = addIndLookupSwitch(onState, labelNoIndexing, false);
            	
            	for(Iterator keyIt = depthToLabel.keySet().iterator(); keyIt.hasNext(); ) {
            		// In the indexing case, the result is the union of all sets in the indexed mapping.
            		Integer key = (Integer)keyIt.next();
            		doAddLabel((Stmt)depthToLabel.get(key));
            		IterationContext context = new IterationContext(key.intValue(), 
            				getFieldLocal(thisLocal, "indexedDisjuncts", mapType));
            		
            		Local result = getNewObject(setClass);
            		
            		startIteration(context);
            		doMethodCall(result, "addAll", singleCollectionType, BooleanType.v(), 
            				getRelevantSet(context));
            		endIteration(context, false);
            		
            		doReturn(result);
            	}
            	
            	doAddLabel(labelNoIndexing);
            	// In the non-indexing case, the result is this.disjuncts
            	doReturn(getNewObject(setClass, singleCollectionType, 
            			getFieldLocal(thisLocal, "disjuncts", setType)));
        } else {
            // Create locals for all the parameters
            List parameterLocals = new LinkedList();
            int parameterIndex = 0;
            Local stateTo = getParamLocal(parameterIndex++, IntType.v());
            parameterLocals.add(onState);
            parameterLocals.add(stateTo);
            for(Iterator varIt = variables.iterator(); varIt.hasNext(); ) {
                parameterLocals.add(getParamLocal(parameterIndex++, 
                        curTraceMatch.bindingType((String)varIt.next())));
            }
            
            // LinkedHashSet to store the result in
            Local result = getNewObject(setClass);
            
            // Disjunct.falseD -- for comparison
            Local falseDisjunct = getStaticFieldLocal(disjunct, "falseD", disjunct.getType());
 

            // Normally we'd do "if this == false then return false", however, our representation
            // of false depends on which state we're on, so we first do a jump based on states.
            
            // We need to differentiate between the following cases:
            // - This state does not use indexing
            // - This state uses indexing. In this case, there will be a distinction on the depth
            //    of the index map.
            Stmt labelNoIndexing = getNewLabel();
            
            Map caseToLabel = addIndLookupSwitch(onState, labelNoIndexing, true);
            
            for(Iterator caseIt = caseToLabel.keySet().iterator(); caseIt.hasNext(); ) {
                List indices = (List)caseIt.next();
                doAddLabel((Stmt)caseToLabel.get(indices));
                int depth = indices.size();
                
                // We need to construct an array Local[] keys that holds the indexing variables
                // bound by the current symbol
                Local[] keys = new Local[depth];
                
                int paramIndex = 2; // Parameters 0 and 1 are the source and target state number.
                for(Iterator varIt = variables.iterator(); varIt.hasNext(); paramIndex++) {
                		String var = (String)varIt.next();
                		int keyIndex = indices.indexOf(var);
                		if(keyIndex >= 0) {
                			// if the variable is primitive, it must be boxed.
                			if(curTraceMatch.isPrimitive(var)) {
                				keys[keyIndex] = getWeakRef((Local)parameterLocals.get(paramIndex), var);
                			} else {
                				keys[keyIndex] = (Local)parameterLocals.get(paramIndex);
                			}
                		}
                }
                
                // keys[] now contains sufficient information to construct the IterationContext
                IterationContext context = new IterationContext(depth, 
                        getFieldLocal(thisLocal, "indexedDisjuncts", mapType),
                        keys);
                
                startIteration(context);
                
                Local relevantSet = getRelevantSet(context);
                
                Local setIterator = getMethodCallResult(relevantSet, "iterator", iteratorType);
                
                Stmt labelLoopBegin = getNewLabel(), labelLoopEnd = getNewLabel();
                
                doAddLabel(labelLoopBegin);
                doJumpIfFalse(getMethodCallResult(setIterator, "hasNext", BooleanType.v()), 
                        labelLoopEnd);
                
                Local curDisjunct = getCastValue(getMethodCallResult(setIterator, "next", objectType), 
                        this.disjunct.getType());
                
                ////////// Cleanup of invalid disjuncts -- if the current disjunct isn't valid,
                // just remove it from the disjunct set and continue with the next.
                // if(!curDisjunct.validateDisjunct(stateTo)) {it.remove(); goto labelLoopBegin;}
                Stmt labelDisjunctValid = getNewLabel();
                List singleInt = new LinkedList();
                singleInt.add(IntType.v());
                doJumpIfTrue(getMethodCallResult(curDisjunct, "validateDisjunct", singleInt,
                        BooleanType.v(), stateTo), labelDisjunctValid);
                doMethodCall(setIterator, "remove", VoidType.v());
                doJump(labelLoopBegin);
                
                doAddLabel(labelDisjunctValid);
                ////////// end cleanup code
                
                Local resultDisjunct = getMethodCallResult(curDisjunct, "addBindingsForSymbol" + symbol,
                        methodFormals, disjunct.getType(), parameterLocals);
                
                // If the result is the false disjunct anyway, there's no point in adding it
                doJumpIfEqual(resultDisjunct, falseDisjunct, labelLoopBegin);
                
                // if the disjunct is not false, add it
                doMethodCall(result, "add", singleObjectType, BooleanType.v(), resultDisjunct);
                
                doJump(labelLoopBegin);
                // end of loop
                
                doAddLabel(labelLoopEnd);
                
                endIteration(context);
                
                doReturn(result);
            }
            
            // The case that doesn't require indexing...
            doAddLabel(labelNoIndexing);
            
            Local relevantSet = getFieldLocal(thisLocal, "disjuncts", setType);
            
            Local setIterator = getMethodCallResult(relevantSet, "iterator", iteratorType);
            
            Stmt labelLoopBegin = getNewLabel(), labelLoopEnd = getNewLabel();
            
            doAddLabel(labelLoopBegin);
            doJumpIfFalse(getMethodCallResult(setIterator, "hasNext", BooleanType.v()), 
                    labelLoopEnd);
            
            Local curDisjunct = getCastValue(getMethodCallResult(setIterator, "next", objectType), 
                    this.disjunct.getType());
            
            ////////// Cleanup of invalid disjuncts -- if the current disjunct isn't valid,
            // just remove it from the disjunct set and continue with the next.
            // if(!curDisjunct.validateDisjunct(stateTo)) {it.remove(); goto labelLoopBegin;}
            Stmt labelDisjunctValid = getNewLabel();
            List singleInt = new LinkedList();
            singleInt.add(IntType.v());
            doJumpIfTrue(getMethodCallResult(curDisjunct, "validateDisjunct", singleInt,
                    BooleanType.v(), stateTo), labelDisjunctValid);
            doMethodCall(setIterator, "remove", VoidType.v());
            doJump(labelLoopBegin);
            
            doAddLabel(labelDisjunctValid);
            ////////// end cleanup code
            
            Local resultDisjunct = getMethodCallResult(curDisjunct, "addBindingsForSymbol" + symbol,
                    methodFormals, disjunct.getType(), parameterLocals);
            
            // If the result is the false disjunct anyway, there's no point in adding it
            doJumpIfEqual(resultDisjunct, falseDisjunct, labelLoopBegin);
            
            // if the disjunct is not false, add it
            doMethodCall(result, "add", singleObjectType, BooleanType.v(), resultDisjunct);
            
            doJump(labelLoopBegin);
            // end of loop
            
            doAddLabel(labelLoopEnd);
            
            doReturn(result);
        }
    }
 
    /**
     * The 'addNegativeBindingsForSymbol' methods in the indexed case are called 'queueNegativeBindingsForSymbol'
     * as they maintain a queue of changes.
     */
    protected void addIndConstraintQueueNegativeBindingsMethods() {
        List params = new LinkedList();
        List args = new LinkedList();

        Iterator syms = curTraceMatch.getSymbols().iterator();

        while (syms.hasNext()) {
            String symbol = (String) syms.next();
            Stmt method_end = getNewLabel();

            params.clear();
            args.clear();

            List sym_binds = curTraceMatch.getVariableOrder(symbol);

            Iterator vars = sym_binds.iterator();
            while (vars.hasNext()) {
                String var = (String) vars.next();
                Type type = curTraceMatch.bindingType(var);

                params.add(type);
            }

            startMethod("queueNegativeBindingsForSymbol" + symbol,
                        params, VoidType.v(), Modifier.PUBLIC);
            Local this_local = getThisLocal();
            Local state = getFieldLocal(this_local, "onState", IntType.v());
            Local[] values = new Local[sym_binds.size()];
            for (int i = 0; i < sym_binds.size(); i++)
                values[i] = getParamLocal(i, (Type) params.get(i));

            Stmt no_index_case = getNewLabel();
            Map indices_to_labels =
               addIndLookupSwitch(state, no_index_case, true);

            Iterator index_lists = indices_to_labels.keySet().iterator();
            while (index_lists.hasNext()) {
                List indices = (List) index_lists.next();
                Stmt label = (Stmt) indices_to_labels.get(indices);
                
                doAddLabel(label);
                addQueueNegativeBindingsBodyIndex(symbol, this_local,
                        values, sym_binds, indices, state);
                
                doJump(method_end);
            }

            doAddLabel(no_index_case);
            addQueueNegativeBindingsBodyNoIndex(symbol, this_local,
                values, sym_binds, state);

            doAddLabel(method_end);
            doReturnVoid();
        }
    }

    /**
     * Adding negative bindings in the case that the current
     * state uses indexing
     */
    protected void addQueueNegativeBindingsBodyIndex(String symbol,
                                  Local this_local, Local[] values,
                                  List sym_binds, List indices, Local state)
    {
        int depth = indices.size();
        Local[] keys = new Local[depth];
        Iterator vars_bound = sym_binds.iterator();
        for(int i = 0; i < values.length; i++) {
            String var = (String)vars_bound.next();
            int index_level = indices.indexOf(var);
            if(index_level >= 0) {
                // if the variable is primitive it needs to be boxed
                if(curTraceMatch.isPrimitive(var)) {
                    keys[index_level] = getWeakRef(values[i], var);
                } else {
                    keys[index_level] = values[i];
                }
            }
        }
        
        // Get the map to iterate over: indexedDisjuncts.
        // Yes, we may actually end up getting disjuncts from
        // indexedDisjuncts_skip, but the indices used in the
        // former are guaranteed to be a superset of those
        // used in the latter.
        Local map = getFieldLocal(this_local, "indexedDisjuncts", mapType);
        Local skip_map = getFieldLocal(this_local,
                "indexedDisjuncts_skip", mapType);
        
        IterationContext context = new IterationContext(depth, map, keys);
        
        startIteration(context);
        
        // look the disjunct up in indexedDisjuncts_skip, but if the result is null,
        // then get it from indexedDisjuncts
        List params = new LinkedList();
        List args = new LinkedList();
        params.add(mapType);
        args.add(skip_map);
        for(int i = 0; i < keys.length; i++) {
            params.add(objectType);
            args.add(context.keys[i]);
        }

        Local source = getMethodCallResult(this_local, "lookup" + depth,
                params, setType, args);
        
        Stmt end_if = getNewLabel();
        doJumpIfNotNull(source, end_if);
        Local original_source = getRelevantSet(context);
        doAssign(source, original_source);
        doAddLabel(end_if);
        
        // do the actual negative bindings bit
        Local result = addQueueNegativeBindingsSetProcessing(symbol, source,
                state, values);
        
        // put the result in indexedDisjunct_skip
        // params and args still contain the values from the call to lookup()
        params.add(setType);
        args.add(result);
        params.add(BooleanType.v());
        args.add(getInt(0));
        doMethodCall(this_local, "overwrite" + depth, params,
                VoidType.v(), args);
        
        endIteration(context);
    }

    /**
     * Adding negative bindings in the case that the current
     * state is not indexed.
     */
    protected void addQueueNegativeBindingsBodyNoIndex(String symbol,
                                  Local this_local, Local[] values,
                                  List sym_binds, Local state)
    {
        Local source = getFieldLocal(this_local, "disjuncts_skip", setType);
        Local result = addQueueNegativeBindingsSetProcessing(symbol, source,
                           state, values);
        doSetField(this_local, "disjuncts_skip", setType, result);
    }

    /**
     * The code-generation common to all cases of adding negative
     * bindings. This generates code that takes a set and calculates
     * the new set produced by adding negative bindings.
     */
    protected Local addQueueNegativeBindingsSetProcessing(String symbol,
                                        Local set, Local state, Local[] values)
    {
        // types and argument for calls
        List params = new LinkedList();
        List args = new LinkedList();

        // create result set
        Local result = getNewObject(setClass);

        Local iter = getMethodCallResult(set, "iterator", iteratorType);
        Stmt loop_test = getNewLabel();
        Stmt invalid = getNewLabel();
        Stmt loop_end = getNewLabel();

        doAddLabel(loop_test);
        Local has_next = getMethodCallResult(iter, "hasNext", BooleanType.v());
        doJumpIfFalse(has_next, loop_end);

        Local next_disjunct =
            getCastValue(getMethodCallResult(iter, "next", objectType),
                         disjunct.getType());

        params.add(IntType.v());
        args.add(state);
        Local is_valid =
            getMethodCallResult(next_disjunct, "validateDisjunct", params,
                         BooleanType.v(), args);
        doJumpIfFalse(is_valid, invalid);

        for (int i = 0; i < values.length; i++) {
            params.add(values[i].getType());
            args.add(values[i]);
        }

        // deal with optimisation:
        //   Disjunct.addNegativeBindingsForX is of type Disjunct
        //                                    if X binds less than 2 variables
        //   otherwise it returns a set
        boolean optimise = values.length < 2;
        Type ret_type   = optimise ? disjunct.getType() : setType;
        String add_name = optimise ? "add"              : "addAll";
        List add_types  = optimise ? singleObjectType   : singleCollectionType;
 
        Local updated = getMethodCallResult(next_disjunct,
            "addNegativeBindingsForSymbol" + symbol, params, ret_type, args);
        doMethodCall(result, add_name, add_types, BooleanType.v(), updated);
        doJump(loop_test);

        doAddLabel(invalid);
        doMethodCall(iter, "remove", VoidType.v());
        doJump(loop_test);

        doAddLabel(loop_end);
        
        // Remove Disjunct.falseD if it has ended up in the set
        doMethodCall(result, "remove", singleObjectType, BooleanType.v(), 
        		getStaticFieldLocal(disjunct, "falseD", disjunct.getType()));
        return result;
    }

    /**
     * Adds small helper methods, like lookup(), lookup2(), overwrite() and queue().
     */
    protected void addIndConstraintHelperMethods() {
        int[] index_depths = curTraceMatch.getIndexingDepths();

        for (int i = 0; i < index_depths.length; i++) {
            addIndConstraintLookupMethod(index_depths[i]);
            addIndConstraintOverwriteMethod(index_depths[i]);
            addIndConstraintQueueMethod(index_depths[i]);
        }

        addIndConstraintUnindexedQueueMethod();
    }

    /**
     * Generate a method to lookup a LinkedHashSet in an index of maps of a
     * specified depth. The generated method will take the map and the correct
     * number of keys as parameters.
     *
     * If there are primitive index variables, they should be boxed before 
     * being passed to this method.
     *
     * @param depth the depth of the index of maps
     */
    protected void addIndConstraintLookupMethod(int depth) {
        String name = "lookup" + depth;
        List param_types = new ArrayList(depth + 1);

        // the parameters to the lookup method are one map, and "depth" keys
        param_types.add(mapType);
        for (int i = 0; i < depth; i++)
            param_types.add(objectType);

        startMethod(name, param_types, setType, Modifier.PUBLIC);
        Local map = getParamLocal(0, mapType);
        
        Local[] keys = new Local[depth];
        for(int i = 1; i <= depth; i++) {
            keys[i-1] = getParamLocal(i, objectType);
        }
        
        IterationContext context = new IterationContext(depth, map, keys);
        
        startIteration(context);
        
        doReturn(getRelevantSet(context));
        
        endIteration(context, false);
        
        doReturn(getNull());
    }
    
    /**
     * Generate a method to overwrite a LinkedHashSet in an index of maps
     * of a specified depth.  The generated method will take as parameters:
     * the map, the correct number of keys, the LinkedHashSet to insert into
     * the map, and a boolean flag for whether or not to cleanup empty
     * sets/maps afterwards.
     * 
     * If there are primitive index variables, they should be boxed before 
     * being passed to this method.
     *
     * @param depth the depth of the index of maps
     */
    protected void addIndConstraintOverwriteMethod(int depth) {
        String name = "overwrite" + depth;
        List param_types = new ArrayList(depth + 3);

        // the parameters to the overwrite method are one map, "depth" keys,
        // a set, and a boolean flag to tell overwrite whether or not it
        // should clean up empty sets/maps
        param_types.add(mapType);
        for (int i = 0; i < depth; i++)
            param_types.add(objectType);
        param_types.add(setType);
        param_types.add(BooleanType.v());

        startMethod(name, param_types, VoidType.v(), Modifier.PUBLIC);

        Local this_local = getThisLocal();

        Local[] keys = new Local[depth];
        Local[] maps = new Local[depth];
        maps[0] = getParamLocal(0, mapType);
        for (int i = 0; i < depth; i++)
            keys[i] = getParamLocal(1 + i, objectType);
        Local set = getParamLocal(depth + 1, setType);
        Local cleanup = getParamLocal(depth + 2, BooleanType.v());

        // cleanup: iff the boolean flag is true, we should check for empty sets and maps.
        // However, assuming that every other operation cleans up behind itself, the only
        // case in which a cleanup is actually needed is if the set we're passed is empty.
        Stmt labelNoCleanup = getNewLabel();
        doJumpIfFalse(cleanup, labelNoCleanup);
        doJumpIfFalse(getMethodCallResult(set, "isEmpty", BooleanType.v()), labelNoCleanup);
        
        // if we're here, we're meant to do a cleanup step, *and* the set we've been passed
        // is empty. This amounts to deleting the currently existing set at the given indices.
        IterationContext context = new IterationContext(depth, maps[0], keys);
        startIteration(context);
        // the code here will only be executed if the keys lead us to a particular set. We simply
        // empty the set and let endIteration perform the cleanup.
        doMethodCall(getRelevantSet(context), "clear", VoidType.v());
        endIteration(context, true);

        doReturnVoid();
        // End cleanup
        
        doAddLabel(labelNoCleanup);

        // the method map.put(...) takes two object parameters
        List put_types = new ArrayList(2);
        put_types.add(objectType);
        put_types.add(objectType);

        for (int i = 1; i < depth; i++) {
            Local child = getMethodCallResult(maps[i-1], "get",
                                              singleObjectType,
                                              objectType, keys[i-1]);

            // if the value is non-null, cast it to a map, else
            // create a new map and insert it
            Stmt null_case = getNewLabel();
            Stmt end_if = getNewLabel();

            doJumpIfNull(child, null_case);
            maps[i] = getCastValue(child, mapType);
            doJump(end_if);

            doAddLabel(null_case);
            child = getNewMapForDepth(this_local, getInt(i));
            List put_args = new ArrayList(2);
            put_args.add(keys[i-1]);
            put_args.add(child);
            doMethodCall(maps[i-1], "put", put_types, objectType, put_args);
            doAssign(maps[i], child);

            doAddLabel(end_if);
        }

        List put_args = new ArrayList(2);
        put_args.add(keys[depth - 1]);
        put_args.add(set);
        doMethodCall(maps[depth - 1], "put", put_types, objectType, put_args);

        doReturnVoid();
    }

    /**
     * Generate a method to queue disjuncts that will be ORd with the
     * constraint when merge() is called on it. This version of the
     * queue method queues the disjuncts using the indexing keys
     * passed as parameters. It is specialised to a particular depth
     * of index.
     *
     * We assume that the indexed versions of queue will never be
     * called on unindexed states.
     *
     * @param depth the depth of the index to which this method is
     *        specialised
     */
    protected void addIndConstraintQueueMethod(int depth)
    {
        List params = new LinkedList();
        List args = new LinkedList();

        // Declare the method signature
        params.add(setType);
        for (int i = 0; i < depth; i++)
            params.add(objectType);
        startMethod("queue", params, VoidType.v(), Modifier.PUBLIC);

        // Assign "this" and each parameter to a local
        Local this_local = getThisLocal();
        Local disjuncts = getParamLocal(0, setType);
        Local[] keys = new Local[depth];
        for (int i = 0; i < depth; i++)
            keys[i] = getParamLocal(i + 1, objectType);

        // Lookup the keys in indexedDisjuncts_tmp
        Local index_tmp =
            getFieldLocal(this_local, "indexedDisjuncts_tmp", mapType);
        params.clear();
        params.add(0, mapType);
        args.add(index_tmp);
        for (int i = 0; i < depth; i++) {
            params.add(objectType);
            args.add(keys[i]);
        }
        Local updated = getMethodCallResult(this_local, "lookup" + depth,
                                            params, setType, args);

        // if updated is null, then we add a new entry to the map
        // otherwise, we add the new disjuncts to the existing
        // indexed set
        Stmt non_null_case = getNewLabel();
        doJumpIfNotNull(updated, non_null_case);

        // overwriteN(indexedDisjuncts_tmp, key1--keyN, disjuncts, true);
        params.add(setType);
        params.add(BooleanType.v());
        args.add(disjuncts);
        args.add(getInt(1));
        doMethodCall(this_local, "overwrite" + depth, params,
                     VoidType.v(), args);
        doReturnVoid();

        // generate: updated.addAll(disjuncts);
        doAddLabel(non_null_case);
        params.clear();
        args.clear();
        params.add(Scene.v().getRefType("java.util.Collection"));
        args.add(disjuncts);
        doMethodCall(updated, "addAll", params, BooleanType.v(), args);
        doReturnVoid();
    }

    /**
     * Generate a method to queue disjuncts that will be ORd with the
     * constraint when merge() is called on it. This version of the
     * queue method queues the disjuncts one-by-one, examining each
     * one in turn to find the keys to index it under.
     */
    protected void addIndConstraintUnindexedQueueMethod()
    {
        List params = new LinkedList();
        List args = new LinkedList();
        params.add(setType);

        startMethod("queue", params, VoidType.v(), Modifier.PUBLIC);
        Local this_local = getThisLocal();
        Local disjuncts = getParamLocal(0, setType);
        Local state = getFieldLocal(this_local, "onState", IntType.v());
        Local index_tmp =
            getFieldLocal(this_local, "indexedDisjuncts_tmp", mapType);

        Stmt no_index_case = getNewLabel();
        Map indices_to_label = addIndLookupSwitch(state, no_index_case, true);

        Iterator index_lists = indices_to_label.keySet().iterator();
        while (index_lists.hasNext()) {
            List indices = (List) index_lists.next();
            Stmt label = (Stmt) indices_to_label.get(indices);

            doAddLabel(label);
            Local iter =
                getMethodCallResult(disjuncts, "iterator", iteratorType);

            Stmt loop_test = getNewLabel();
            Stmt loop_end = getNewLabel();

            doAddLabel(loop_test);
            Local has_next =
                getMethodCallResult(iter, "hasNext", BooleanType.v());
            doJumpIfFalse(has_next, loop_end);

            Local next_disjunct =
                getCastValue(getMethodCallResult(iter, "next", objectType),
                             disjunct.getType());

            params.clear();
            args.clear();
            params.add(mapType);
            args.add(index_tmp);

            // get keys from disjunct
            int depth = indices.size();
            for (int i = 0; i < depth; i++) {
                String varname = (String) indices.get(i);
                String name = "get$" + varname;
                Type type = curTraceMatch.bindingType(varname);

                Local var = getMethodCallResult(next_disjunct, name, type);
                if (curTraceMatch.isPrimitive(varname))
                    var = getWeakRef(var, varname);

                params.add(objectType);
                args.add(var);
            }

            Local updated = getMethodCallResult(this_local, "lookup" + depth,
                                                params, setType, args);

            Stmt end_if = getNewLabel();
            doJumpIfNotNull(updated, end_if);
            doAssign(updated, getNewObject(setClass));
            params.add(setType);
            params.add(BooleanType.v());
            args.add(updated);
            args.add(getInt(0));
            doMethodCall(this_local, "overwrite" + depth, params,
                         VoidType.v(), args);

            doAddLabel(end_if);
            params.clear();
            doMethodCall(updated, "add", singleObjectType,
                         BooleanType.v(), next_disjunct);

            doJump(loop_test);
            doAddLabel(loop_end);

            doReturnVoid();
        }

        // the non-indexed case
        doAddLabel(no_index_case);
        Local disjuncts_tmp =
            getFieldLocal(this_local, "disjuncts_tmp", setType);
        doMethodCall(disjuncts_tmp, "addAll", singleCollectionType,
                     BooleanType.v(), disjuncts);
        doReturnVoid();
    }

    /**
     * Creates a lookup switch on a state number. It jumps to a
     * label based on either:
     * (a) the depth of the index at the state (if names_matter is false)
     * (b) the variables indexed on at the state (if names_matter is true)
     *
     * In case (a) this method returns a map from index-depths to the
     * label dealing with that case.
     *
     * In case (b) this method returns a map from a list of indexed
     * variables to the label dealing with that case.
     *
     * In both cases, the default label is taken when the state is not
     * indexed at all.
     *
     * @param lookup_key    the Jimple value on which the lookup is taken
     * @param default_label the default label for the table lookup
     * @param names_matter  true if, and only if, states with different
     *                      indexing variables but the same number of
     *                      indices should be treated separately
     */
    protected Map addIndLookupSwitch(Local lookup_key, Stmt default_label,
                                     boolean names_matter)
    {
        TMStateMachine sm = ((TMStateMachine) curTraceMatch.getStateMachine());
        Iterator states = sm.getStateIterator();
        Map case_to_label = new HashMap();

        List lookup_values = new LinkedList();
        List lookup_labels = new LinkedList();

        while (states.hasNext()) {
            SMNode state = (SMNode) states.next();

            if (state.indices == null || state.indices.isEmpty())
                continue;

            Object table_case;
            if (names_matter)
                table_case = state.indices;
            else
                table_case = new Integer(state.indices.size());
            
            Stmt label = (Stmt) case_to_label.get(table_case);

            if (label == null) {
                label = getNewLabel();
                case_to_label.put(table_case, label);
            }

            lookup_values.add(getInt(state.getNumber()));
            lookup_labels.add(label);
        }

        if (!case_to_label.isEmpty())
            doLookupSwitch(lookup_key, lookup_values, lookup_labels,
                           default_label);

        return case_to_label;
    }

    /**
     * Creates a lookup switch on a state number. Creates a new label for each state (unless
     * 'nonIndexingToDefault' is true, in which case states that don't use indexing don't
     * get special labels and thus map to the default label).
     * 
     * Returns a map from SMNodes to labels dealing with each SMNode.
     * @param key the Local containing the state number to switch on
     * @param labelDefault the default label
     * @param nonIndexingToDefault true iff nonindexing states should be handled by the 'default' case
     * @return map from SMNodes to labels
     */
	protected Map addStateLookupSwitch(Local key, Stmt labelDefault, boolean nonIndexingToDefault) {
		List switchValues = new LinkedList(), switchLabels = new LinkedList();
		Map stateToLabel = new HashMap();
		for(Iterator stateIt = ((TMStateMachine)curTraceMatch.getStateMachine()).getStateIterator(); 
						stateIt.hasNext(); ) {
			SMNode state = (SMNode) stateIt.next();
			
			if((state.indices == null || state.indices.isEmpty()) && nonIndexingToDefault)
				continue;
			
			Stmt label = (Stmt)stateToLabel.get(state);
			
			if(label == null) {
				label = getNewLabel();
				stateToLabel.put(state, label);
			}
			
			switchValues.add(getInt(state.getNumber()));
			switchLabels.add(label);
		}
		
		if(!stateToLabel.isEmpty())
			doLookupSwitch(key, switchValues, switchLabels, labelDefault);
		
		return stateToLabel;
	}
}
