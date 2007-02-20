
package abc.main;

import polyglot.util.ErrorInfo;
import polyglot.util.ErrorQueue;
import soot.SootClass;
import soot.jimple.toolkits.base.ExceptionCheckerError;
import soot.jimple.toolkits.base.ExceptionCheckerErrorReporter;
import abc.polyglot.util.ErrorInfoFactory;

public class GotCheckedExceptionError implements ExceptionCheckerErrorReporter {
    private ErrorQueue error_queue; // For reporting errors and warnings
    public GotCheckedExceptionError(ErrorQueue error_queue) {
        this.error_queue = error_queue;
    }
    public void reportError(ExceptionCheckerError err) {
        SootClass exctype=err.excType();

        ErrorInfo e=ErrorInfoFactory.newErrorInfo
            (ErrorInfo.SEMANTIC_ERROR,
             "The exception "+exctype+" must be either caught "+
             "or declared to be thrown",
             err.method(),
             err.throwing());

        error_queue.enqueue(e);
    }
}
