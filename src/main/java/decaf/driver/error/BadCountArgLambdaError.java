package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class BadCountArgLambdaError extends DecafError {
    public int expected;
    public int given;

    public BadCountArgLambdaError(Pos pos, int exptected, int given)
    {
        super(pos);
        this.expected = exptected;
        this.given = given;
    }

    @Override
    public String getErrMsg() {
        return "lambda expression expects " + expected + " argument(s) but " + given + " given";
    }
}
