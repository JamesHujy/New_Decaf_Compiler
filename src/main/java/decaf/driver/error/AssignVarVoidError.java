package decaf.driver.error;

import decaf.frontend.tree.Pos;
public class AssignVarVoidError extends DecafError{

    private String methodName;

    public AssignVarVoidError(Pos pos, String methodName)
    {
        super(pos);
        this.methodName = methodName;
    }

    @Override
    protected String getErrMsg() {
        return "cannot declare identifier '"+methodName+"' as void type";
    }

}
