package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class AssignMethodError extends DecafError {

    public String name;
    public AssignMethodError(Pos pos, String name)
    {
        super(pos);
        this.name = name;
    }

    @Override
    public String getErrMsg()
    {
        return "cannot assign value to class member method '"+name+"'";
    }
}