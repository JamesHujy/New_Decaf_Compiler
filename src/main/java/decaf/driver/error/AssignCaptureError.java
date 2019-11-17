package decaf.driver.error;

import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;

public class AssignCaptureError extends DecafError {
    public AssignCaptureError(Pos pos)
    {
        super(pos);
    }

    @Override
    public String getErrMsg()
    {
        return "cannot assign value to captured variables in lambda expression";
    }
}
