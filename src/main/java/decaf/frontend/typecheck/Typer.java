package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.*;
import decaf.frontend.symbol.*;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.*;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyScope;

import java.sql.BatchUpdateException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.LogManager;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {

    public Typer(Config config) {
        super("typer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
        ctx.open(method.symbol.scope);
        if(!method.isAbstract())
        {
            method.body.accept(this, ctx);
            if (!method.symbol.type.returnType.isVoidType() && !method.body.returns) {
                issue(new MissingReturnError(method.body.pos));
            }
        }
        ctx.close();
    }

    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
        }
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
        ctx.close();
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);
        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;
        if(ctx.judgeLambda())
        {
            if(stmt.lhs instanceof Tree.VarSel && !((Tree.VarSel)stmt.lhs).receiver.isPresent())
            {
                var varsel = (Tree.VarSel) stmt.lhs;
                var captureSymbol = ctx.lookupBefore(varsel.name, ctx.currentLambda().pos);
                if(captureSymbol.isPresent() && !ctx.currentLambda().scope.find(varsel.name).isPresent()
                        && !captureSymbol.get().domain().equals(ctx.currentClass().scope)){
                    issue(new AssignCaptureError(stmt.pos));
                }
            }
            return;
        }
        if(stmt.lhs instanceof Tree.VarSel)
        {
            var varsel = (Tree.VarSel) stmt.lhs;

            if(varsel.isMethod)
            {
                issue(new AssignMethodError(stmt.pos, varsel.name));
            }
        }
        if (lt.noError() && (!rt.subtypeOf(lt))) {
            issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
        }
    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }


    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
        // if-stmt returns a value iff both branches return
        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loopLevel--;
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
        }
        loopLevel--;
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        if(ctx.judgeLambda()) {
            if(stmt.expr.isPresent())
            {
                stmt.expr.get().accept(this, ctx);
                stmt.returns = true;
                stmt.returnType = stmt.expr.get().type;
            }
            else
            {
                stmt.returns = false;
                stmt.returnType = BuiltInType.VOID;
            }

            var actual = stmt.returnType;

            var lambdaSymbol = ctx.currentLambda();

            lambdaSymbol.returnTypeList.add(actual);
        }
        else{
            var expected = ctx.currentMethod().type.returnType;
            stmt.expr.ifPresent(e -> e.accept(this, ctx));
            var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
            if (actual.noError() && !actual.subtypeOf(expected)) {
                issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
            }
            stmt.returns = stmt.expr.isPresent();
            stmt.returnType = actual;
        }

    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;
        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);

        if (clazz.isPresent()) {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
            if(expr.symbol.isAbstract)
            {
                issue(new NewAbstractClassError(expr.pos, expr.clazz.name));
            }
        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }

    private boolean allowClassNameVar = false;

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {

        if (expr.receiver.isEmpty()) {
            // Variable, which should be complicated since a legal variable could refer to a local var,
            // a visible member var, and a class name.
            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));

            if (symbol.isPresent()) {
                if (symbol.get().isVarSymbol()) {
                    var var = (VarSymbol) symbol.get();
                    expr.symbol = var;
                    expr.type = var.type;
                    if (var.isMemberVar()) {
                        if (ctx.currentMethod().isStatic()) {
                            issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                        } else {
                            expr.setThis();
                        }
                    }
                    return;
                }
                if (symbol.get().isClassSymbol()) { // special case: a class name
                    var clazz = (ClassSymbol) symbol.get();
                    expr.type = clazz.type;
                    expr.isClassName = true;
                    return;
                }

                if(symbol.get().isMethodSymbol())
                {
                    var method = (MethodSymbol) symbol.get();
                    expr.type = method.type;
                    if(!method.isStatic() && ctx.currentMethod().isStatic())
                    {
                        issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                    }
                    else
                        expr.isMethod = true;
                    return;
                }
            }

            expr.type = BuiltInType.ERROR;
            issue(new UndeclVarError(expr.pos, expr.name));
            return;
        }
        // has receiver
        var receiver = expr.receiver.get();
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        var rt = receiver.type;
        expr.type = BuiltInType.ERROR;

        if(receiver instanceof Tree.VarSel)
        {
            var v1 = (Tree.VarSel) receiver;
            if(v1.isClassName)
            {
                var clazz = ctx.getClass(v1.name);
                String methodName = expr.name;

                var symbol = clazz.scope.lookup(methodName);
                if(symbol.isPresent()) {
                    if (symbol.get().isMethodSymbol()) {
                        var method = (MethodSymbol) symbol.get();
                        expr.type = method.type;
                        if (!method.isStatic()) {
                            issue(new NotClassFieldError(expr.pos, expr.name, v1.type.toString()));
                        }
                        else
                            expr.isMethod = true;

                    } else if (symbol.get().isVarSymbol()) {
                        var var = (VarSymbol) symbol.get();
                        expr.symbol = var;
                        expr.type = var.type;
                        issue(new NotClassFieldError(expr.pos, expr.name, v1.type.toString()));
                    }
                }
                return;
            }
        }

        if(!rt.noError())
            return;
        if(rt.isClassType())
        {
            var ct = (ClassType) rt;
            var field = ctx.getClass(ct.name).scope.lookup(expr.name);
            if (field.isPresent()) {
                if(field.get().isVarSymbol())
                {
                    var var = (VarSymbol) field.get();
                    if (var.isMemberVar()) {
                        expr.symbol = var;
                        expr.type = var.type;
                        if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                            // member vars are protected
                            issue(new FieldNotAccessError(expr.pos, expr.name, ct.toString()));
                        }
                    }
                }
                else if(field.get().isMethodSymbol())
                {
                    var method = (MethodSymbol) field.get();
                    expr.symbol = new VarSymbol(expr.name, method.type, method.pos);
                    expr.type = method.type;
                    expr.isMethod = true;
                }
                else {
                    issue(new NotClassFieldError(expr.pos, expr.name, ct.toString()));
                }
            } else{
                issue(new FieldNotFoundError(expr.pos, expr.name, ct.toString()));
            }
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if (!at.isArrayType()) {
            issue(new NotArrayError(expr.array.pos));
            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        System.out.println("visit call "+expr+expr.pos.toString());
        expr.type = BuiltInType.ERROR;
        Type rt;
        Tree.VarSel varsel;
        Tree.Lambda lambda;
        if(expr.expr instanceof Tree.VarSel)
        {
            varsel = (Tree.VarSel) expr.expr;
            if (varsel.receiver.isPresent()) {
                var receiver = varsel.receiver.get();

                allowClassNameVar = true;
                receiver.accept(this, ctx);
                allowClassNameVar = false;

                if (receiver instanceof Tree.VarSel) {
                    var v1 = (Tree.VarSel) receiver;
                    if (v1.isClassName) {
                        // Special case: invoking a static method, like MyClass.foo()
                        typeCall(expr, false, v1.name, ctx, ctx.currentMethod().isStatic());
                    }
                    else if(varsel.name.equals("length")) {
                        if (v1.type.isArrayType())
                        {
                            if(!expr.args.isEmpty())
                            {
                                issue(new BadLengthArgError(expr.pos, expr.args.size()));
                            }
                            expr.isArrayLength = true;
                            expr.type = BuiltInType.INT;
                        }
                        else
                        {
                            issue(new NotClassFieldError(expr.expr.pos, varsel.name, v1.type.toString()));
                        }
                    }
                }
                else if (receiver instanceof Tree.NewClass)
                {
                    var type = receiver.type;
                    if(type.noError()&&type.isClassType())
                    {
                        boolean requireStatic = !((Tree.NewClass) receiver).symbol.name.equals(ctx.currentClass().name) && ctx.currentMethod().isStatic();
                        typeCall(expr, false, ((Tree.NewClass) receiver).symbol.name, ctx, requireStatic);
                    }
                    else if(varsel.name.equals("length")) {
                        if (receiver.type.isArrayType())
                        {
                            if(!expr.args.isEmpty())
                            {
                                issue(new BadLengthArgError(expr.pos, expr.args.size()));
                            }
                            expr.isArrayLength = true;
                            expr.type = BuiltInType.INT;
                        }
                        else
                        {
                            issue(new NotClassFieldError(expr.expr.pos, varsel.name, receiver.type.toString()));
                        }
                    }
                }
            }
            else {
                typeCall(expr, true, "", ctx, false);
            }
        }

        else if(expr.expr instanceof Tree.Lambda)
        {
            lambda = (Tree.Lambda) expr.expr;
            lambda.accept(this, ctx);
            var returntype = ((FunType) lambda.type).returnType;
            expr.type = returntype;
            if(expr.args.size() != lambda.symbol.argTypes.size())
                issue(new BadCountArgLambdaError(expr.pos, lambda.symbol.argTypes.size(), expr.args.size()));
        }
    }

    private void typeCall(Tree.Call call, boolean thisClass, String className, ScopeStack ctx, boolean requireStatic) {
        System.out.println("varsel"+call+call.pos);
        var clazz = thisClass ? ctx.currentClass() : ctx.getClass(className);
        var varSel = (Tree.VarSel) call.expr;
        String methodName = varSel.name;
        var symbol = clazz.scope.lookup(methodName);
        var localSymbol = ctx.lookupBefore(methodName, call.pos);

        if (symbol.isPresent()) {
            if (symbol.get().isMethodSymbol()) {
                var method = (MethodSymbol) symbol.get();
                call.symbol = method;
                call.type = method.type.returnType;
                if (requireStatic && !method.isStatic()) {
                    issue(new NotClassFieldError(call.expr.pos, methodName, clazz.type.toString()));
                    return;
                }

                // Cannot call this's member methods in a static method
                if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
                    issue(new RefNonStaticError(call.expr.pos, ctx.currentMethod().name, method.name));
                }

                // typing args
                var args = call.args;
                for (var arg : args) {
                    arg.accept(this, ctx);
                }

                // check signature compatibility
                if (method.type.arity() != args.size()) {
                    issue(new BadArgCountError(call.pos, method.name, method.type.arity(), args.size()));
                }

                var iter1 = method.type.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }
            } else {
                issue(new NotCallableError(call.pos, symbol.get().type.toString()));
            }
        } else if(localSymbol.isPresent()) {
            var localSymbolGet = localSymbol.get();
            if(localSymbolGet.isMethodSymbol())
            {
                var args = call.args;
                var localSymbolType = (FunType)localSymbolGet.type;
                call.type = localSymbolType.returnType;
                System.out.println(call.type);
                if (localSymbolType.argTypes.size() != args.size()) {
                    issue(new BadArgCountError(call.pos, localSymbolGet.name, localSymbolType.argTypes.size(), args.size()));
                }
            }
            else if(localSymbolGet.isVarSymbol() && localSymbolGet.type.isFuncType())
            {
                var args = call.args;
                var lambdaType = (FunType)localSymbolGet.type;
                call.type = lambdaType.returnType;
                System.out.println(call.type);
                if(lambdaType.argTypes.size() != args.size())
                {
                    issue(new BadArgCountError(call.pos, localSymbolGet.name, lambdaType.argTypes.size(), args.size()));
                }
            }
            else
            {
                issue(new NotCallableError(call.pos, localSymbolGet.type.toString()));
            }

        }
        else {
            if(!thisClass)
                issue(new FieldNotFoundError(call.expr.pos, methodName, clazz.type.toString()));
            else
                issue(new UndeclVarError(call.expr.pos, methodName));
        }
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {
        if (!stmt.isVar&&stmt.initVal.isEmpty()) return;

        Tree.Expr initVal;
        if(!stmt.isVar)
            initVal = stmt.initVal.get();
        else
            initVal = stmt.VarExpr;

        localVarDefPos = Optional.ofNullable(stmt.id.pos);
        initVal.accept(this, ctx);
        localVarDefPos = Optional.empty();

        if(!stmt.isVar)
        {
            var lt = stmt.symbol.type;
            var rt = initVal.type;


            if (lt.noError() && !rt.subtypeOf(lt)) {
                issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
            }
        }
        else
        {
            if(initVal.type.isVoidType())
            {
                issue(new AssignVarVoidError(stmt.id.pos, stmt.id.name));
            }

            stmt.symbol.type = initVal.type;
        }
    }

    public Type getSuperior(List<Type> typeList)
    {
        if(typeList.size()==0)
            return BuiltInType.VOID;
        var condidate = typeList.get(0);
        if(condidate.isBaseType() || condidate.isVoidType())
        {
            for(var type:typeList)
            {
                if(!type.eq(condidate))
                    return BuiltInType.ERROR;
            }
            return condidate;
        }
        else if (condidate.isClassType())
        {
            var classType = (ClassType) condidate;
            while(true){
                boolean hasError = false;
                for(var type:typeList)
                {
                    if(!type.subtypeOf(classType))
                    {
                        hasError = true;
                        break;
                    }
                }
                if(hasError)
                {
                    if(classType.superType.isPresent())
                        classType = classType.superType.get();
                    else
                        return BuiltInType.ERROR;
                }
                else return classType;
            }
        }
        else if(condidate.isFuncType())
        {
            var functype = (FunType) condidate;
            ArrayList<ArrayList<Type>> typeListList = new ArrayList<>();
            ArrayList<Type> returnTypeList = new ArrayList<>();
            for(var type:typeList)
            {
                if(!type.isFuncType())
                    return BuiltInType.ERROR;
                else {
                    var temp = (FunType) type;
                    if(temp.argTypes.size() != functype.argTypes.size())
                        return BuiltInType.ERROR;
                }
            }
            for(int i = 0;i < functype.argTypes.size();i++)
            {
                ArrayList<Type> tempList = new ArrayList<>();
                typeListList.add(tempList);
            }
            for(var type:typeList)
            {
                var temp = (FunType) type;
                returnTypeList.add(((FunType) type).returnType);
                int index = 0;
                for(var paraType:temp.argTypes)
                {
                    var list = typeListList.get(index);
                    list.add(paraType);
                    typeListList.set(index, list);
                    index += 1;
                }
            }
            ArrayList<Type> finalPara = new ArrayList<>();
            for(var list:typeListList)
            {
                var ans = getInferior(list);

                if(ans.eq(BuiltInType.ERROR))
                {
                    return BuiltInType.ERROR;
                }

                finalPara.add(ans);
            }
            var finalReturn = getSuperior(returnTypeList);
            if(finalReturn.noError())
                return new FunType(finalReturn, finalPara);
            else
                return BuiltInType.ERROR;
        }
        else if(condidate.eq(BuiltInType.NULL))
        {
            if(typeList.size() == 1)
                return BuiltInType.NULL;
            else
            {
                typeList.remove(0);
                return getSuperior(typeList);
            }
        }
        return condidate;
    }

    public Type getInferior(List<Type> typeList)
    {
        var condidate = typeList.get(0);
        if(condidate.isBaseType() || condidate.isVoidType())
        {
            for(var type:typeList)
            {
                if(!type.eq(condidate))
                    return BuiltInType.ERROR;
            }
            return condidate;
        }
        else if (condidate.isClassType())
        {
            for(var type:typeList)
            {
                boolean isInf = true;
                for(var typeR:typeList)
                {
                    if(!type.subtypeOf(typeR))
                    {
                        isInf = false;
                        break;
                    }
                }
                if(isInf)
                    return type;

            }
            return BuiltInType.ERROR;
        }
        else if(condidate.isFuncType())
        {
            var functype = (FunType) condidate;
            ArrayList<ArrayList<Type>> typeListList = new ArrayList<>();
            ArrayList<Type> returnTypeList = new ArrayList<>();
            for(var type:typeList)
            {
                if(!type.isFuncType())
                    return BuiltInType.ERROR;
                else {
                    var temp = (FunType) type;
                    if(temp.argTypes.size() != functype.argTypes.size())
                        return BuiltInType.ERROR;
                }
            }
            for(int i = 0;i < functype.argTypes.size();i++)
            {
                ArrayList<Type> tempList = new ArrayList<>();
                typeListList.add(tempList);
            }
            for(var type:typeList)
            {
                var temp = (FunType) type;
                returnTypeList.add(((FunType) type).returnType);
                int index = 0;
                for(var paraType:temp.argTypes)
                {
                    var list = typeListList.get(index);
                    list.add(paraType);
                    typeListList.set(index, list);
                    index += 1;
                }
            }
            ArrayList<Type> finalPara = new ArrayList<>();
            for(var list:typeListList)
            {
                var ans = getSuperior(list);
                if(ans.eq(BuiltInType.ERROR))
                    return BuiltInType.ERROR;
                finalPara.add(ans);
            }
            var finalReturn = getInferior(returnTypeList);
            if(finalReturn.noError())
                return new FunType(finalReturn, finalPara);
            else
                return BuiltInType.ERROR;
        }
        else if(condidate.eq(BuiltInType.NULL))
            return BuiltInType.NULL;
        return condidate;
    }

    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx) {
        ctx.open(lambda.scope);
        if(lambda.isBlock)
        {
            lambda.body.accept(this,ctx);
            if(lambda.symbol.returnTypeList.size() == 0) {
                lambda.symbol.setReturnType(BuiltInType.VOID);
                lambda.type = lambda.symbol.type;
            }
            else {
                boolean reported = false;
                for(var type:lambda.symbol.returnTypeList)
                {
                    if(!type.isVoidType() && !lambda.body.returns && !reported)
                    {
                        issue(new MissingReturnError(lambda.body.pos));
                        reported = true;
                    }
                }
                var returnType = getSuperior(lambda.symbol.returnTypeList);
                if(returnType.noError())
                {
                    lambda.symbol.setReturnType(returnType);
                    lambda.type = lambda.symbol.type;
                }
                else
                    issue(new IncompatibleReturnError(lambda.body.pos));
            }
        }
        else
        {
            lambda.expr.accept(this, ctx);
            lambda.symbol.setReturnType(lambda.expr.type);
            lambda.type = lambda.symbol.type;
        }
        ctx.close();
    }
    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();
}