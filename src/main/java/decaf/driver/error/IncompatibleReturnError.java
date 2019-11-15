package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class IncompatibleReturnError extends DecafError {
    public IncompatibleReturnError(Pos pos)
    {
        super(pos);
    }

    @Override
    public String getErrMsg(){
        return "incompatible return types in blocked expression";
    }
}
