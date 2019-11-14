package decaf.frontend.type;

import decaf.frontend.tree.Tree;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TFuncType extends FunType {
    public TFuncType(Type returnType, List<Type> typeList)
    {
        super(returnType, typeList);
    }

    @Override
    public boolean eq(Type that) {
        return this.toString().equals(that.toString());
    }

    @Override
    public boolean isTFuncType(){
        return true;
    }
}
