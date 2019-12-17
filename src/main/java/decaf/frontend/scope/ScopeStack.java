package decaf.frontend.scope;

import decaf.frontend.symbol.*;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;

import java.util.*;
import java.util.function.Predicate;

/**
 * A symbol table, which is organized as a stack of scopes, maintained by {@link decaf.frontend.typecheck.Namer}.
 * <p>
 * A typical full scope stack looks like the following:
 * <pre>
 *     LocalScope   --- stack top (current scope)
 *     ...          --- many nested local scopes
 *     LocalScope
 *     FormalScope
 *     ClassScope
 *     ...          --- many parent class scopes
 *     ClassScope
 *     GlobalScope  --- stack bottom
 * </pre>
 * Make sure the global scope is always at the bottom, and NO class scope appears in neither formal nor local scope.
 *
 * @see Scope
 */
public class ScopeStack {

    /**
     * The global scope.
     */
    public final GlobalScope global;

    public ScopeStack(GlobalScope global) {
        this.global = global;
    }

    /**
     * The current scope, at the stack top.
     *
     * @return current scope
     */
    public Scope currentScope() {
        if (scopeStack.isEmpty()) return global;
        return scopeStack.peek();
    }

    /**
     * The innermost (top most on stack) class we now locate in.
     *
     * @return class symbol
     */
    public ClassSymbol currentClass() {
        Objects.requireNonNull(currClass);
        return currClass;
    }

    /**
     * The method we now locate in.
     *
     * @return method symbol
     */
    public MethodSymbol currentMethod() {
        Objects.requireNonNull(currMethod);
        return currMethod;
    }

    public LambdaSymbol currentLambda() {
        return lambdaScopeStack.peek().getOwner();
    }

    /**
     * Open a scope.
     * <p>
     * If the current scope is a class scope, then we must first push all its super classes and then push this.
     * Otherwise, only push the `scope`.
     * <p>
     * REQUIRES: you don't open multiple class scopes, and never open a class scope when the current scope is
     * a formal/local scope.
     */
    public void open(Scope scope) {
        assert !scope.isGlobalScope();
        if (scope.isClassScope()) {
            assert !currentScope().isFormalOrLocalScopeOrLambda();
            var classScope = (ClassScope) scope;
            classScope.parentScope.ifPresent(this::open);
            currClass = classScope.getOwner();
        } else if (scope.isFormalScope()) {
            var formalScope = (FormalScope) scope;
            currMethod = formalScope.getOwner();

        } else if (scope.isLambdaScope()) {
            var lambdaScope = (LambdaScope) scope;
            lambdaScopeStack.push(lambdaScope);
        }
        scopeStack.push(scope);
    }

    /**
     * Close the current scope.
     * <p>
     * If the current scope is a class scope, then we must close this class and all super classes. Since the global
     * scope is never pushed to the actual {@code scopeStack}, we need to pop all scopes!
     * Otherwise, only pop the current scope.
     */
    public void close() {

        assert !scopeStack.isEmpty();
        Scope scope = scopeStack.pop();
        if(scope.isLambdaScope())
        {
            var lambdaSymbol = lambdaScopeStack.pop().getOwner();
            if(!lambdaScopeStack.empty())
            {
                var tempTop = lambdaScopeStack.pop();
                System.out.println(tempTop.getOwner());
                for(var captured: lambdaSymbol.catchedSymbol)
                {
                    if(captured instanceof Tree.This)
                        tempTop.getOwner().catchedSymbol.add(captured);
                    else{
                        var defineIn = ((VarSymbol)captured).domain();
                        while(defineIn.isLocalScope())
                            defineIn = ((LocalScope)defineIn).getParentScope();
                        if(defineIn != tempTop)
                            if(!tempTop.getOwner().catchedSymbol.contains(captured))
                                tempTop.getOwner().catchedSymbol.add(captured);
                    }
                }
                lambdaScopeStack.push(tempTop);
            }
        }

        if (scope.isClassScope()) {
            while (!scopeStack.isEmpty()) {
                scopeStack.pop();
            }
        }
    }

    /**
     * Lookup a symbol by name. By saying "lookup", the user expects that the symbol is found.
     * In this way, we will always search in all possible scopes and returns the innermost result.
     *
     * @param key symbol's name
     * @return innermost found symbol (if any)
     */
    public Optional<Symbol> lookup(String key) {
        return findWhile(key, whatever -> true, whatever -> true);
    }

    /**
     * Same with {@link #lookup} but we restrict the symbol's position to be before the given {@code pos}.
     *
     * @param key symbol's name
     * @param pos position
     * @return innermost found symbol before {@code pos} (if any)
     */
    public Optional<Symbol> lookupBefore(String key, Pos pos) {
        return findWhile(key, whatever -> true, s -> !(s.domain().isLocalScope() && s.pos.compareTo(pos) >= 0));
    }

    /**
     * Find if a symbol is conflicting with some already defined symbol. Rules:
     * First, if the current scope is local scope or formal scope, then it cannot conflict with any already defined
     * symbol till the formal scope, and it cannot conflict with any names in the global scope.
     * <p>
     * Second, if the current scope is class scope or global scope, then it cannot conflict with any already defined
     * symbol.
     * <p>
     * NO override checking is issued here -- the type checker is in charge of this!
     *
     * @param key symbol's name
     * @return innermost conflicting symbol (if any)
     */
    public Optional<Symbol> findConflict(String key) {
        if (currentScope().isFormalOrLocalScopeOrLambda())
            return findWhile(key, Scope::isFormalOrLocalScopeOrLambda, whatever -> true).or(() -> global.find(key));
        return lookup(key);
    }

    public Optional<Symbol> findConflictLocally(String key) {
        if (currentScope().isLocalScope())
            return findWhile(key, Scope::isLocalScope, whatever -> true).or(() -> global.find(key));
        return lookup(key);
    }

    /**
     * Tell if a class is already defined in the global scope.
     *
     * @param key class's name
     * @return true/false
     */
    public boolean containsClass(String key) {
        return global.containsKey(key);
    }

    /**
     * Lookup a class in the global scope.
     *
     * @param key class's name
     * @return class symbol (if found)
     */
    public Optional<ClassSymbol> lookupClass(String key) {
        return Optional.ofNullable(global.getClass(key));
    }

    /**
     * Get a class from global scope.
     *
     * @param key class's name
     * @return class symbol (if found) or null (if not found)
     */
    public ClassSymbol getClass(String key) {
        return global.getClass(key);
    }

    /**
     * Declare a symbol in the current scope.
     *
     * @param symbol symbol
     * @see Scope#declare
     */
    public void declare(Symbol symbol) {
        currentScope().declare(symbol);
    }

    public boolean judgeLambda(){
        return !lambdaScopeStack.empty();
    }
    private Stack<Scope> scopeStack = new Stack<>();
    private Stack<LambdaScope> lambdaScopeStack = new Stack<>();
    private Map<String, Pos> definingVar = new TreeMap<>();
    private ClassSymbol currClass;
    private MethodSymbol currMethod;

    private Optional<Symbol> findWhile(String key, Predicate<Scope> cond, Predicate<Symbol> validator) {
        ListIterator<Scope> iter = scopeStack.listIterator(scopeStack.size());
        while (iter.hasPrevious()) {
            var scope = iter.previous();
            if (!cond.test(scope)) return Optional.empty();
            var symbol = scope.find(key);
            if (symbol.isPresent() && validator.test(symbol.get())) return symbol;
        }
        return cond.test(global) ? global.find(key) : Optional.empty();
    }

    public void addDefining(String name, Pos pos){
        definingVar.put(name, pos);
    }

    public void removeDefining(String name) {
        definingVar.remove(name);
    }

    public boolean judgeContain(String var){
        return definingVar.keySet().contains(var);
    }

    public Pos getPos(String name){
        return definingVar.get(name);
    }

}
