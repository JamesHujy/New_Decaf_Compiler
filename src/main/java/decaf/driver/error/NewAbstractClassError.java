package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class NewAbstractClassError extends DecafError{
    private String className;

    public NewAbstractClassError(Pos pos, String className) {
        super(pos);
        this.className = className;
    }

    @Override
    protected String getErrMsg() {
        return "cannot instantiate abstract class '" + className + "'";
    }
}