package decaf.frontend.symbol;

import decaf.frontend.scope.LambdaScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.type.BuiltInType;
import decaf.frontend.type.FunType;
import decaf.frontend.type.TFuncType;
import decaf.frontend.type.Type;

import java.util.ArrayList;
import java.util.List;

public final class LambdaSymbol extends Symbol{

    public Type returnType;

    public List<Type> argTypes;

    public LambdaScope scope;

    public ArrayList<Type> returnTypeList = new ArrayList<>();

    public LambdaSymbol(Type returnType, List<Type> argTypes, LambdaScope scope, Pos pos) {
        super("lambda@"+pos.toString(), BuiltInType.NULL, pos);
        this.returnType = returnType;
        this.argTypes = argTypes;
        this.scope = scope;
        type = new TFuncType(returnType, argTypes);
    }

    @Override
    protected String str() {
        return String.format("function %s : %s", name, type);
    }
    @Override
    public boolean isLambdaSymbol() {
        return true;
    }

    public void setReturnType(Type returnType) {
        this.returnType = returnType;
        type = new FunType(returnType, argTypes);
    }
}
