package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class VoidAsParaError extends DecafError {

    public VoidAsParaError(Pos pos)
    {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "arguments in function type must be non-void known type";
    }
}
