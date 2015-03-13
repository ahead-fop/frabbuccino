## Introduction

_Frabbuccino_ is a highly extensible Java and AspectJ compiler. It is a complete feature-oriented refactoring of _abc_, The AspectBench Compiler for AspectJ.

## What is abc?

abc is a framework for experimentation with AspectJ language features and compiler optimizations. It was specifically designed to support _extensions_ such as syntax, semantics, optimizing passes, etc. abc has been used in a variety of research projects to explore ideas with actual implementation in AspectJ.

## What is Frabbuccino?

The goal of Frabbuccino is to take abc and demonstrate that it can be made more extensible, more flexible, and more maintainable by refactoring it using the [AHEAD tool suite](http://www.cs.utexas.edu/~schwartz/ATS.html). Frabbuccino's design is based on the concept of [feature-oriented programming](http://en.wikipedia.org/wiki/Feature-oriented_programming), the study of feature modularity.

In Frabbuccino, the original design of abc is broken apart into multiple _features_, those chunks of code that are heavily correlated and generally form coherent components in the end product. For example, in [AspectJ](http://aspectj.org), the pointcut [`call(MethodPattern)`](http://www.eclipse.org/aspectj/doc/released/progguide/quick.html#quick-pointcuts) might be considered a feature, because it embodies a single concept. The code to implement that in abc is not local to a few classes. Rather, it is spread across multiple components including the parser, AST visitors, and the runtime. However, by extracting those chunks of code into a single module, we transform abc from a compiler framework with extensions into a set of features that can be composed to generate a desired compiler.
