package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.*;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;

import java.sql.SQLSyntaxErrorException;
import java.util.*;

/**
 * The namer phase: resolve all symbols defined in the abstract syntax tree and store them in symbol tables (i.e.
 * scopes).
 */
public class Namer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Namer(Config config) {
        super("namer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        tree.globalScope = new GlobalScope();
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        var classes = new TreeMap<String, Tree.ClassDef>();

        // Check conflicting definitions. If any, ignore the redefined ones.
        for (var clazz : program.classes) {
            var earlier = classes.get(clazz.name);
            if (earlier != null) {
                issue(new DeclConflictError(clazz.pos, clazz.name, earlier.pos));
            } else {
                classes.put(clazz.name, clazz);
            }
        }

        // Make sure the base class exists. If not, ignore the inheritance.
        for (var clazz : classes.values()) {
            clazz.parent.ifPresent(p -> {
                if (classes.containsKey(p.name)) { // good
                    clazz.superClass = classes.get(p.name);
                } else { // bad
                    issue(new ClassNotFoundError(clazz.pos, p.name));
                    clazz.parent = Optional.empty();
                }
            });
        }

        // Make sure any inheritance does not form a cycle.
        checkCycles(classes);
        // If so, return with errors.
        if (hasError()) return;

        // So far, class inheritance is well-formed, i.e. inheritance relations form a forest of trees. Now we need to
        // resolve every class definition, make sure that every member (variable/method) is well-typed.
        // Realizing that a class type can be used in the definition of a class member, either a variable or a method,
        // we shall first know all the accessible class types in the program. These types are wrapped into
        // `ClassSymbol`s. Note that currently, the associated `scope` is empty because member resolving has not
        // started yet. All class symbols are stored in the global scope.
        for (var clazz : classes.values()) {
            createClassSymbol(clazz, ctx.global);
        }

        // Now, we can resolve every class definition to fill in its class scope table. To check if the overriding
        // behaves correctly, we should first resolve super class and then its subclasses.
        for (var clazz : classes.values()) {
            clazz.accept(this, ctx);
        }

        // Finally, let's locate the main class, whose name is 'Main', and contains a method like:
        //  static void main() { ... }
        boolean found = false;
        for (var clazz : classes.values()) {
            if (clazz.name.equals("Main") && !clazz.isAbstract) {
                var symbol = clazz.symbol.scope.find("main");
                if (symbol.isPresent() && symbol.get().isMethodSymbol()) {
                    var method = (MethodSymbol) symbol.get();
                    if (method.isStatic() && method.type.returnType.isVoidType() && method.type.arity() == 0) {
                        method.setMain();
                        program.mainClass = clazz.symbol;
                        clazz.symbol.setMainClass();
                        found = true;
                    }
                }
            }
        }
        if (!found) {
            issue(new NoMainClassError());
        }
    }

    /**
     * Check if class inheritance form cycle(s).
     *
     * @param classes a map between class names to their definitions
     */
    private void checkCycles(Map<String, Tree.ClassDef> classes) {
        var visitedTime = new TreeMap<String, Integer>();
        for (var clazz : classes.values()) {
            visitedTime.put(clazz.name, 0);
        }

        var time = 1; // nodes in the same inheritance path/chain have the same time
        Tree.ClassDef from = null;
        for (var node : classes.keySet()) {
            if (visitedTime.get(node) != 0) { // already done, skip
                continue;
            }

            // visit from this node
            while (true) {
                if (visitedTime.get(node) == 0) { // not visited yet
                    visitedTime.put(node, time);
                    var clazz = classes.get(node);
                    if (clazz.parent.isPresent()) {
                        // continue to visit its parent
                        node = clazz.parent.get().name;
                        from = clazz;
                    } else break;
                } else if (visitedTime.get(node) == time) { // find a cycle
                    issue(new BadInheritanceError(from.pos));
                    break;
                } else { // this node is visited earlier, also done
                    break;
                }
            }
            time++;
        }
    }

    /**
     * Create a class symbol and declare in the global scope.
     *
     * @param clazz  class definition
     * @param global global scope
     */
    private void createClassSymbol(Tree.ClassDef clazz, GlobalScope global) {
        if (global.containsKey(clazz.name)) return;

        if (clazz.parent.isPresent()) {
            createClassSymbol(clazz.superClass, global);
            var base = global.getClass(clazz.parent.get().name);
            var type = new ClassType(clazz.name, base.type);
            var scope = new ClassScope(base.scope);
            var symbol = new ClassSymbol(clazz.isAbstract, clazz.name, base, type, scope, clazz.pos);
            global.declare(symbol);
            clazz.symbol = symbol;
        } else {
            var type = new ClassType(clazz.name);
            var scope = new ClassScope();
            var symbol = new ClassSymbol(clazz.isAbstract, clazz.name, type, scope, clazz.pos);
            global.declare(symbol);
            clazz.symbol = symbol;
        }
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
    }

    @Override
    public void visitUnary(Tree.Unary unary, ScopeStack ctx){
        unary.operand.accept(this, ctx);
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {

        if (clazz.resolved) return;

        if (clazz.hasParent()) {
            clazz.superClass.accept(this, ctx);
        }

        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }

        boolean hasAbstractError = false;
        if(!clazz.isAbstract && clazz.hasParent())
        {
            var parentAbstract = clazz.superClass.symbol.getAbstractMethod();
            for(var method:parentAbstract)
            {

                if(!clazz.symbol.scope.containsKey(method))
                {
                    issue(new BadAbstractMethodError(clazz.pos, clazz.name));
                    hasAbstractError = true;
                    break;
                }
            }
        }
        if(!hasAbstractError)
        {
            for (var symbol : clazz.symbol.scope)
            {
                if(symbol.isMethodSymbol())
                {
                    var methodSymbol = (MethodSymbol) symbol;
                    if(methodSymbol.isAbstract() && !clazz.isAbstract)
                    {
                        issue(new BadAbstractMethodError(clazz.pos, clazz.name));
                        break;
                    }
                }
            }
        }
        ctx.close();
        clazz.resolved = true;
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        expr.expr.accept(this, ctx);
        for (var para : expr.args)
            para.accept(this, ctx);
    }

    @Override
    public void visitExprEval(Tree.ExprEval expr, ScopeStack ctx)
    {
        expr.expr.accept(this, ctx);
    }

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx)
    {
        if(expr.receiver.isPresent())
            expr.receiver.get().accept(this, ctx);
    }

    @Override
    public void visitNewArray(Tree.NewArray newArray, ScopeStack ctx)
    {
        newArray.length.accept(this, ctx);
    }

    @Override
    public void visitClassTest(Tree.ClassTest classTest, ScopeStack ctx)
    {
        classTest.obj.accept(this, ctx);
    }

    @Override
    public void visitClassCast(Tree.ClassCast classCast, ScopeStack ctx)
    {
        classCast.obj.accept(this, ctx);
    }

    @Override
    public void visitVarDef(Tree.VarDef varDef, ScopeStack ctx) {
        varDef.typeLit.accept(this, ctx);
        var earlier = ctx.findConflict(varDef.name);
        if (earlier.isPresent()) {
            if (earlier.get().isVarSymbol() && earlier.get().domain() != ctx.currentScope()) {
                issue(new OverridingVarError(varDef.pos, varDef.name));
            } else {
                issue(new DeclConflictError(varDef.pos, varDef.name, earlier.get().pos));
            }
            return;
        }
        if (varDef.typeLit.type.eq(BuiltInType.VOID)) {
            issue(new BadVarTypeError(varDef.pos, varDef.name));
            return;
        }

        if (varDef.typeLit.type.noError()) {
            var symbol = new VarSymbol(varDef.name, varDef.typeLit.type, varDef.pos);
            ctx.declare(symbol);
            varDef.symbol = symbol;
        }
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        var earlier = ctx.findConflict(method.name);
        if (earlier.isPresent()) {
            if (earlier.get().isMethodSymbol()) { // may be overriden
                var suspect = (MethodSymbol) earlier.get();
                if(!suspect.isAbstract() && method.isAbstract())
                {
                    issue(new DeclConflictError(method.pos, method.name, earlier.get().pos));
                }
                if (suspect.domain() != ctx.currentScope() && !suspect.isStatic() && !method.isStatic()) {
                    // Only non-static methods can be overriden, but the type signature must be equivalent.
                    var formal = new FormalScope();
                    typeMethod(method, ctx, formal);
                    if (method.type.subtypeOf(suspect.type)) { // override success
                        var symbol = new MethodSymbol(method.name, method.type, formal, method.pos, method.modifiers,
                                ctx.currentClass());
                        ctx.declare(symbol);
                        method.symbol = symbol;
                        ctx.open(formal);
                        if(!method.isAbstract())
                            method.body.accept(this, ctx);
                        ctx.close();
                    } else {
                        issue(new BadOverrideError(method.pos, method.name, suspect.owner.name));
                    }
                    return;
                }
            }

            issue(new DeclConflictError(method.pos, method.name, earlier.get().pos));
            return;
        }
        var formal = new FormalScope();
        typeMethod(method, ctx, formal);
        var symbol = new MethodSymbol(method.name, method.type, formal, method.pos, method.modifiers,
                ctx.currentClass());
        ctx.declare(symbol);
        method.symbol = symbol;
        ctx.open(formal);
        if(!method.isAbstract())
        {
            method.body.accept(this, ctx);
        }
        ctx.close();
    }

    private void typeMethod(Tree.MethodDef method, ScopeStack ctx, FormalScope formal) {
        method.returnType.accept(this, ctx);
        ctx.open(formal);
        if (!method.isStatic()) ctx.declare(VarSymbol.thisVar(ctx.currentClass().type, method.id.pos));
        var argTypes = new ArrayList<Type>();
        for (var param : method.params) {
            param.accept(this, ctx);
            argTypes.add(param.typeLit.type);
        }
        method.type = new FunType(method.returnType.type, argTypes);
        ctx.close();
    }

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        block.scope = new LocalScope(ctx.currentScope());
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef def, ScopeStack ctx) {
        def.typeLit.accept(this, ctx);
        if(ctx.judgeContain(def.name))
        {
            issue(new DeclConflictError(def.pos, def.name, ctx.getPos(def.name)));
        }
        if(!def.isVar && def.initVal.isPresent())
        {
            if(def.initVal.get() instanceof Tree.Lambda)
                ctx.addDefining(def.name, def.pos);

            def.initVal.get().accept(this, ctx);
            if(def.initVal.get() instanceof Tree.Lambda)
                ctx.removeDefining(def.name);
        }
        else if(def.isVar)
        {
            if(def.VarExpr instanceof Tree.Lambda)
                ctx.addDefining(def.name, def.pos);

            def.VarExpr.accept(this, ctx);
            if(def.VarExpr instanceof Tree.Lambda)
                ctx.removeDefining(def.name);

        }
        ctx.removeDefining(def.name);


        var earlier = ctx.findConflict(def.name);
        if (earlier.isPresent()) {
            issue(new DeclConflictError(def.pos, def.name, earlier.get().pos));
            return;
        }

        if (def.typeLit.type.eq(BuiltInType.VOID)) {
            issue(new BadVarTypeError(def.pos, def.name));
            return;
        }

        if (def.typeLit.type.noError()) {
            var symbol = new VarSymbol(def.name, def.typeLit.type, def.id.pos);
            ctx.declare(symbol);
            def.symbol = symbol;
        }
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        loop.scope = new LocalScope(ctx.currentScope());
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        loop.cond.accept(this, ctx);
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitAssign(Tree.Assign assign, ScopeStack ctx){
        assign.lhs.accept(this, ctx);
        assign.rhs.accept(this, ctx);
    }

    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        stmt.cond.accept(this, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        loop.cond.accept(this, ctx);
        loop.body.accept(this, ctx);
    }


    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        if(stmt.expr.isPresent()) {
            stmt.expr.get().accept(this, ctx);
        }
    }

    @Override
    public void visitPrint(Tree.Print print, ScopeStack ctx) {
        for(var expr:print.exprs)
            expr.accept(this, ctx);
    }

    @Override
    public void visitIndexSel(Tree.IndexSel indexSel, ScopeStack ctx){
        indexSel.array.accept(this, ctx);
        indexSel.index.accept(this, ctx);
    }
    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx) {
        lambda.scope = new LambdaScope(ctx.currentScope());
        ctx.open(lambda.scope);

        var argsTypes = new ArrayList<Type>();
        for(var paras:lambda.params) {
            paras.accept(this, ctx);
            paras.typeLit.accept(this, ctx);
            argsTypes.add(paras.typeLit.type);
        }
        lambda.lambdaSymbol = new LambdaSymbol(BuiltInType.NULL, argsTypes, (LambdaScope) ctx.currentScope(), lambda.pos);


        lambda.type = lambda.lambdaSymbol.type;
        ctx.declare(lambda.lambdaSymbol);
        lambda.scope.setOwner(lambda.lambdaSymbol);

        if(lambda.isBlock)
        {
            lambda.body.accept(this,ctx);
        }
        else
        {
            var scope = new LocalScope(ctx.currentScope());
            ctx.open(scope);
            lambda.expr.accept(this, ctx);
            ctx.close();
        }
        ctx.close();
    }
}