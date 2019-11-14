package decaf.frontend.scope;

import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.tree.Tree;


public final class LambdaScope extends Scope {

    public LambdaSymbol owner;
    public LambdaScope(Scope parent)
    {
        super(Kind.LAMBDA);
        ((LocalScope) parent).nested.add(this);
    }

    public void setOwner(LambdaSymbol owner){
        this.owner = owner;
    }

    public LambdaSymbol getOwner() {
        return owner;
    }

    @Override
    public boolean isLambdaScope(){
        return true;
    }

    private LocalScope nested;

    void setNested(LocalScope scope) {
        nested = scope;
    }

    public LocalScope nestedLocalScope() {
        return nested;
    }
}
