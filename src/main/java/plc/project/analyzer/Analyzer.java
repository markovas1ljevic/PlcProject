package plc.project.analyzer;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import java.lang.Iterable;



public final class Analyzer implements Ast.Visitor<Ir, AnalyzeException> {

    private Scope scope;

    public Analyzer(Scope scope) {
        this.scope = scope;
    }

    @Override
    public Ir.Source visit(Ast.Source ast) throws AnalyzeException {
        var statements = new ArrayList<Ir.Stmt>();
        for (var statement : ast.statements()) {
            statements.add(visit(statement));
        }
        return new Ir.Source(statements);
    }

    private Ir.Stmt visit(Ast.Stmt ast) throws AnalyzeException {
        return (Ir.Stmt) visit((Ast) ast); //helper to cast visit(Ast.Stmt) to Ir.Stmt
    }

    @Override
    public Ir.Stmt.Let visit(Ast.Stmt.Let ast) throws AnalyzeException {
        // check fi the variable is in the defined scope
        if (scope.get(ast.name(), true).isPresent()) {
            throw new AnalyzeException("Variable '" + ast.name() + "' is already defined.");
        }

        // if the declared type is present, then resolve it
        Optional<Type> declaredType = Optional.empty();
        if (ast.type().isPresent()) {
            String typeName = ast.type().get();
            if (!Environment.TYPES.containsKey(typeName)) {
                throw new AnalyzeException("Unknown type: " + typeName);
            }
            declaredType = Optional.of(Environment.TYPES.get(typeName));
        }

        // analyze the value expression if its present
        Optional<Ir.Expr> value = ast.value().isPresent()
                ? Optional.of((Ir.Expr) visit(ast.value().get()))
                : Optional.empty();

        // resolve the final variable type
        Optional<Type> inferredType = declaredType.isPresent()
                ? declaredType
                : value.map(Ir.Expr::type);

        Type resolvedType = inferredType.orElse(Type.ANY); // fallback to ANY

        // if the value is present, ensure that its compatible with the type
        if (value.isPresent()) {
            requireSubtype(value.get().type(), resolvedType);
        }

        // define the variable within the scope
        scope.define(ast.name(), resolvedType);

        // return ir stmt
        return new Ir.Stmt.Let(ast.name(), resolvedType, value);
    }



    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        // check if the function name is already defined in the current scope
        if (scope.get(ast.name(), true).isPresent()) {
            throw new AnalyzeException("Function name '" + ast.name() + "' is already defined in the current scope");
        }

        // verify that the names are unique
        List<String> paramNames = ast.parameters();
        for (int i = 0; i < paramNames.size(); i++) {
            for (int j = i + 1; j < paramNames.size(); j++) {
                if (paramNames.get(i).equals(paramNames.get(j))) {
                    throw new AnalyzeException("Duplicate parameter name: " + paramNames.get(i));
                }
            }
        }

        // get parameter types (use Any if not specified)
        List<Type> parameterTypes = new ArrayList<>();
        for (int i = 0; i < ast.parameters().size(); i++) {
            Type type = Type.ANY;
            if (i < ast.parameterTypes().size() && ast.parameterTypes().get(i).isPresent()) {
                String typeName = ast.parameterTypes().get(i).get();
                if (!Environment.TYPES.containsKey(typeName)) {
                    throw new AnalyzeException("Unknown parameter type: " + typeName);
                }
                type = Environment.TYPES.get(typeName);
            }
            parameterTypes.add(type);
        }

        // get return type (any if not specified)
        Type returnType = Type.ANY;
        if (ast.returnType().isPresent()) {
            String typeName = ast.returnType().get();
            if (!Environment.TYPES.containsKey(typeName)) {
                throw new AnalyzeException("Unknown return type: " + typeName);
            }
            returnType = Environment.TYPES.get(typeName);
        }

        // define the function in current scope
        Type functionType = new Type.Function(parameterTypes, returnType);
        scope.define(ast.name(), functionType);

        // create parameters for Ir
        List<Ir.Stmt.Def.Parameter> irParameters = new ArrayList<>();

        // child scope for function body
        Scope functionScope = new Scope(scope);

        // save the current scope
        Scope parentScope = scope;
        scope = functionScope;

        // define the parameters in the new scope
        for (int i = 0; i < ast.parameters().size(); i++) {
            String paramName = ast.parameters().get(i);
            Type paramType = parameterTypes.get(i);
            scope.define(paramName, paramType);
            irParameters.add(new Ir.Stmt.Def.Parameter(paramName, paramType));
        }

        // define $RETURNS variable in the scope
        scope.define("$RETURNS", returnType);

        // analyze all body statements
        List<Ir.Stmt> irStatements = new ArrayList<>();
        for (Ast.Stmt stmt : ast.body()) {
            irStatements.add((Ir.Stmt) visit(stmt));
        }

        // return to parent scope
        scope = parentScope;

        // return the ir function
        return new Ir.Stmt.Def(ast.name(), irParameters, returnType, irStatements);
    }

    @Override
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        // analyze condition expression
        Ir.Expr condition = (Ir.Expr) visit(ast.condition());

        // verify it is a boolean
        if (!condition.type().equals(Type.BOOLEAN)) {
            throw new AnalyzeException("If condition must be of type Boolean, got " + condition.type());
        }

        // analyze the body of then statements and create a new scope
        List<Ir.Stmt> thenStatements = new ArrayList<>();


        Scope parentScope = scope;
        scope = new Scope(parentScope);

        // analyze then statements
        for (Ast.Stmt stmt : ast.thenBody()) {
            thenStatements.add((Ir.Stmt) visit(stmt));
        }

        // return to parent scope
        scope = parentScope;

        // analyze the else body
        List<Ir.Stmt> elseStatements = new ArrayList<>();

        // create new scope for else body
        scope = new Scope(parentScope);

        // else statements
        for (Ast.Stmt stmt : ast.elseBody()) {
            elseStatements.add((Ir.Stmt) visit(stmt));
        }

        // return to parent scope
        scope = parentScope;

        // create and return if statement
        return new Ir.Stmt.If(condition, thenStatements, elseStatements);
    }

    @Override
    public Ir.Stmt.For visit(Ast.Stmt.For ast) throws AnalyzeException {
        // analyze the ir expression
        Ir.Expr expression = (Ir.Expr) visit(ast.expression());

        // verify the expression is of type iterable
        requireSubtype(expression.type(), Type.ITERABLE);

        // create the child scope
        Scope parent = scope;
        scope = new Scope(parent);

        // define the loop variable as an integer
        scope.define(ast.name(), Type.INTEGER);

        // analyze the loop body
        List<Ir.Stmt> bodyStatements = new ArrayList<>();
        for (Ast.Stmt stmt : ast.body()) {
            bodyStatements.add((Ir.Stmt) visit(stmt));
        }

        // set scope to parent
        scope = parent;

        // return the ir
        return new Ir.Stmt.For(ast.name(), Type.INTEGER, expression, bodyStatements);
    }


    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {
        // get the expected return type from scope
        Type expected = scope.get("$RETURNS", false)
                .orElseThrow(() -> new AnalyzeException("Cannot return outside of a function."));
        if (expected == null) {
            throw new AnalyzeException("Cannot return outside of a function.");
        }

        Ir.Expr value;

        // if the return value exists, analyze it and check subtype
        if (ast.value().isPresent()) {
            value = visit(ast.value().get());
            requireSubtype(expected, value.type());
        } else {
            // no return value = Nil
            value = new Ir.Expr.Literal(null, Type.NIL);
            requireSubtype(expected, Type.NIL);
        }

        // return Ir
        return new Ir.Stmt.Return(Optional.of(value));
    }


    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException {
        if (ast.expression() instanceof Ast.Expr.Variable variable) {
            var ir = visit(variable);
            var value = visit(ast.value());
            requireSubtype(ir.type(), value.type());
            return new Ir.Stmt.Assignment.Variable(ir, value);
        }

        throw new AnalyzeException("Invalid assignment, must be a var");
    }


    private Ir.Expr visit(Ast.Expr ast) throws AnalyzeException {
        return (Ir.Expr) visit((Ast) ast); //helper to cast visit(Ast.Expr) to Ir.Expr
    }

    @Override
    public Ir.Expr.Literal visit(Ast.Expr.Literal ast) throws AnalyzeException {
        var type = switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case String _ -> Type.STRING;

            default -> throw new AssertionError(ast.value().getClass());
        };
        return new Ir.Expr.Literal(ast.value(), type);
    }

    @Override
    public Ir.Expr.Group visit(Ast.Expr.Group ast) throws AnalyzeException {
        // analyze the contained expression
        Ir.Expr expression = visit(ast.expression());

        // create and return a new group expr with the analyzed inner expr using the type of the inner expr
        return new Ir.Expr.Group(expression);
    }

    @Override
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        Ir.Expr left = (Ir.Expr) visit(ast.left());
        Ir.Expr right = (Ir.Expr) visit(ast.right());
        String operator = ast.operator();

        switch (operator) {
            // string concatentation/numeric concatenation
            case "+" -> {
                if (left.type().equals(Type.STRING) || right.type().equals(Type.STRING)) {

                    requireSubtype(left.type(), Type.EQUATABLE);
                    requireSubtype(right.type(), Type.EQUATABLE);
                    return new Ir.Expr.Binary(operator, left, right, Type.STRING);
                }

                // both must be numeric and the same type
                requireSubtype(left.type(), Type.COMPARABLE);
                requireSubtype(right.type(), Type.COMPARABLE);

                if (!left.type().equals(right.type())) {
                    throw new AnalyzeException("Operands to '+' must be of the same numeric type.");
                }

                return new Ir.Expr.Binary(operator, left, right, left.type());
            }

            // arithmetic operations
            case "-", "*", "/", "%" -> {
                requireSubtype(left.type(), Type.COMPARABLE);
                requireSubtype(right.type(), Type.COMPARABLE);

                if (!left.type().equals(right.type())) {
                    throw new AnalyzeException("Operands to '" + operator + "' must be of the same numeric type.");
                }

                return new Ir.Expr.Binary(operator, left, right, left.type());
            }

            // comparison operations
            case "<", "<=", ">", ">=" -> {
                requireSubtype(left.type(), Type.COMPARABLE);
                requireSubtype(right.type(), Type.COMPARABLE);

                if (!left.type().equals(right.type())) {
                    throw new AnalyzeException("Operands to '" + operator + "' must be of the same comparable type.");
                }

                return new Ir.Expr.Binary(operator, left, right, Type.BOOLEAN);
            }

            // equality operations
            case "==", "!=" -> {
                requireSubtype(left.type(), Type.EQUATABLE);
                requireSubtype(right.type(), Type.EQUATABLE);

                if (!left.type().equals(right.type())) {
                    throw new AnalyzeException("Operands to '" + operator + "' must be of the same equatable type.");
                }

                return new Ir.Expr.Binary(operator, left, right, Type.BOOLEAN);
            }

            // logical operations
            case "AND", "OR" -> {
                if (!left.type().equals(Type.BOOLEAN) || !right.type().equals(Type.BOOLEAN)) {
                    throw new AnalyzeException("Operands to '" + operator + "' must be BOOLEAN.");
                }

                return new Ir.Expr.Binary(operator, left, right, Type.BOOLEAN);
            }

            default -> throw new AnalyzeException("Unknown binary operator: " + operator);
        }
    }


    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var type = scope.get(ast.name(), false)
        .orElseThrow(() -> new AnalyzeException("Variable" + ast.name() + " is not defined."));
        return new Ir.Expr.Variable(ast.name(), (type));
    }

    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        // analyze receiver expr
        Ir.Expr receiver = visit(ast.receiver());

        // ensure the receiver is of Type.Object
        if (!(receiver.type() instanceof Type.Object objectType)) {
            throw new AnalyzeException("Receiver must be of type Object.");
        }

        // ensure the property is defined in the object's field scope
        var propertyType = objectType.scope().get(ast.name(), true);
        if (propertyType == null) {
            throw new AnalyzeException("Property '" + ast.name() + "' is not defined in the object.");
        }

        // return the new expr with the resolved property type (im goated)
        return new Ir.Expr.Property(receiver, ast.name(), Type.STRING);
    }


    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        // ensure the function name is defined within the scope
        Type type = scope.get(ast.name(), false)
                .orElseThrow(() -> new AnalyzeException("Function '" + ast.name() + "' is not defined."));

        // check if its a function type
        if (!(type instanceof Type.Function functionType)) {
            throw new AnalyzeException("Symbol '" + ast.name() + "' is not a function.");
        }

        // verify arg count is matching
        if (ast.arguments().size() != functionType.parameters().size()) {
            throw new AnalyzeException("Incorrect number of arguments for function '" + ast.name() + "'.");
        }

        // analyze args and check types
        List<Ir.Expr> arguments = new ArrayList<>();
        for (int i = 0; i < ast.arguments().size(); i++) {
            Ast.Expr argAst = ast.arguments().get(i);
            Ir.Expr argIr = visit(argAst);
            arguments.add(argIr);

            Type expected = functionType.parameters().get(i);
            Type actual = argIr.type();
            requireSubtype(actual, expected);
        }

        // return IR node
        return new Ir.Expr.Function(ast.name(), arguments, functionType.returns());
    }




    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        Ir.Expr receiver = visit(ast.receiver());

        if (!(receiver.type() instanceof Type.Object objectType)) {
            throw new AnalyzeException("Receiver must be of type Object.");
        }

        // get method type
        var methodTypeOpt = objectType.scope().get(ast.name(), true);
        if (methodTypeOpt.isEmpty() || !(methodTypeOpt.get() instanceof Type.Function methodType)) {
            throw new AnalyzeException("Method '" + ast.name() + "' is not defined or is not a function.");
        }

        // check arguments
        var args = new ArrayList<Ir.Expr>();
        for (var expr : ast.arguments()) {
            args.add(visit(expr));
        }

        // check argument count
        if (methodType.parameters().size() != args.size()) {
            throw new AnalyzeException("Argument count mismatch for method '" + ast.name() + "'");
        }

        // check argument types
        for (int i = 0; i < args.size(); i++) {
            requireSubtype(args.get(i).type(), methodType.parameters().get(i));
        }

        return new Ir.Expr.Method(receiver, ast.name(), args, methodType.returns());
    }


    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        // check that the name is not in env types
        if (ast.name().isPresent() && Environment.TYPES.containsKey(ast.name().get())) {
            throw new AnalyzeException("Object name cannot be a predefined type: " + ast.name().get());
        }

        // create an object scope with a null parent
        Scope objectScope = new Scope(null);

        // use a set to track names
        java.util.Set<String> definedNames = new java.util.HashSet<>();


        java.util.List<Ir.Stmt.Let> fields = new java.util.ArrayList<>();
        for (Ast.Stmt.Let field : ast.fields()) {
            if (definedNames.contains(field.name())) {
                throw new AnalyzeException("Duplicate field/method name: " + field.name());
            }
            definedNames.add(field.name());

            // analyze field value
            Optional<Ir.Expr> value;
            if (field.value().isPresent()) {
                value = Optional.of(visit(field.value().get()));
            } else {
                value = Optional.empty();
            }

            // determine type
            Type type;
            if (field.type().isPresent()) {
                type = Environment.TYPES.getOrDefault(field.type().get(), Type.ANY);
            } else if (value.isPresent()) {
                type = value.get().type();
            } else {
                throw new AnalyzeException("Field " + field.name() + " must have a type or a value");
            }

            // define variable within object scope
            objectScope.define(field.name(), type);

            // add to fields list
            fields.add(new Ir.Stmt.Let(field.name(), type, value));
        }

        // create the object type now
        Type.Object objectType = new Type.Object(objectScope);

        // analyze all methods
        java.util.List<Ir.Stmt.Def> methods = new java.util.ArrayList<>();
        for (Ast.Stmt.Def method : ast.methods()) {
            if (definedNames.contains(method.name())) {
                throw new AnalyzeException("Duplicate field/method name: " + method.name());
            }
            definedNames.add(method.name());

            // process parameters
            java.util.List<Ir.Stmt.Def.Parameter> parameters = new java.util.ArrayList<>();
            java.util.List<Type> parameterTypes = new java.util.ArrayList<>();

            // get parameter types
            for (int i = 0; i < method.parameters().size(); i++) {
                String paramName = method.parameters().get(i);
                Type paramType;

                if (i < method.parameterTypes().size() && method.parameterTypes().get(i).isPresent()) {
                    paramType = Environment.TYPES.getOrDefault(method.parameterTypes().get(i).get(), Type.ANY);
                } else {
                    throw new AnalyzeException("Parameter " + paramName + " must have a type");
                }

                parameters.add(new Ir.Stmt.Def.Parameter(paramName, paramType));
                parameterTypes.add(paramType);
            }

            // create method scope as child of objectscope
            Scope methodScope = new Scope(objectScope);

            // define this in method scope
            methodScope.define("this", objectType);

            // define parameters in method scope
            for (int i = 0; i < method.parameters().size(); i++) {
                String paramName = method.parameters().get(i);
                Type paramType = parameters.get(i).type();
                methodScope.define(paramName, paramType);
            }

            // set current scope to method scope for analyzing body
            Scope previousScope = scope;
            scope = methodScope;

            // analyze method body
            java.util.List<Ir.Stmt> bodyStmts = new java.util.ArrayList<>();
            for (Ast.Stmt stmt : method.body()) {
                bodyStmts.add(visit(stmt));
            }

            // restore th previous scope
            scope = previousScope;

            // get the return type
            Type returnType = method.returnType().isPresent()
                    ? Environment.TYPES.getOrDefault(method.returnType().get(), Type.ANY)
                    : Type.ANY;

            // define method within object scope
            Type.Function functionType = new Type.Function(parameterTypes, returnType);
            objectScope.define(method.name(), functionType);

            // add to methods
            methods.add(new Ir.Stmt.Def(method.name(), parameters, returnType, bodyStmts));
        }

        // return the object expression
        return new Ir.Expr.ObjectExpr(ast.name(), fields, methods, objectType);
    }

    public static void requireSubtype(Type type, Type other) throws AnalyzeException {
        if (type.equals(other) || other.equals(Type.ANY)) {
            return;
        }

        // nil is a subtype of equatable
        if (type.equals(Type.NIL) && other.equals(Type.EQUATABLE)) {
            return;
        }

        // equatable subtypes
        if (other.equals(Type.EQUATABLE) &&
                (type.equals(Type.NIL) ||
                        type.equals(Type.BOOLEAN) ||
                        type.equals(Type.INTEGER) ||
                        type.equals(Type.DECIMAL) ||
                        type.equals(Type.STRING) ||
                        type.equals(Type.COMPARABLE) ||
                        type.equals(Type.ITERABLE))) {
            return;
        }

        // comparable subtypes
        if (other.equals(Type.COMPARABLE) &&
                (type.equals(Type.BOOLEAN) ||
                        type.equals(Type.INTEGER) ||
                        type.equals(Type.DECIMAL) ||
                        type.equals(Type.STRING))) {
            return;
        }

        // otherwise, not a valid subtype
        throw new AnalyzeException(type + " is not a subtype of " + other);
    }


}
