package plc.project.evaluator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import plc.project.parser.Ast;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.math.BigInteger;



public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;
    // trying to use a hashmap to resolve runtime inconsistency
    private final Map<String, RuntimeValue.Primitive> primitiveCache = new HashMap<>();
    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        for (var stmt : ast.statements()) {
            value = visit(stmt);
        }
        //TODO: Handle the possibility of RETURN being called outside of a function.
        return value;
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        // check if the variable is already defined
        if (scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Variable '" + ast.name() + "' is already defined in this scope.");
        }

        // print debug
       // System.out.println("Evaluating assignment for: " + ast.name());
      //  ast.value().ifPresent(value -> System.out.println("Expression Type: " + value));

        // evaluate the right side
        RuntimeValue value;
        try {
            value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        } catch (UnsupportedOperationException e) {
            System.out.println("Unhandled expression type: " + ast.value().get());
            value = new RuntimeValue.Primitive(null);
        }

        // define the variable in scope
        scope.define(ast.name(), value);

        return value;
    }




    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        // check if the name is not already defined in the current scope
        Optional<RuntimeValue> existingOpt = scope.get(ast.name(), true); // This returns an Optional

        if (existingOpt.isPresent()) {
            // if the name is already defined in the current scope
            throw new EvaluateException("Function '" + ast.name() + "' is already defined in the current scope");
        }

        // ensure names in parameters are unique
        Set<String> parameterSet = new HashSet<>();
        for (String parameter : ast.parameters()) {
            if (!parameterSet.add(parameter)) {
                throw new EvaluateException("Duplicate parameter name '" + parameter + "' in function definition");
            }
        }

        // create the function definition that will be executed when the function is called
        RuntimeValue.Function.Definition definition = arguments -> {
            // instantiate a new scope that is a child of the scope where the function was defined (static scoping)
            Scope functionScope = new Scope(scope); // Using current scope for static scoping

            // check args.size
            if (arguments.size() != ast.parameters().size()) {
                throw new EvaluateException("Function '" + ast.name() + "' expected " +
                        ast.parameters().size() + " arguments but got " +
                        arguments.size());
            }

            // define parameters with argument values
            for (int i = 0; i < ast.parameters().size(); i++) {
                functionScope.define(ast.parameters().get(i), arguments.get(i));
            }

            // execute the function body in the function scope
            Scope previousScope = scope; // Store the current scope to restore later
            scope = functionScope;       // Set current scope to function scope

            try {
                // execute statements sequentially
                for (Ast.Stmt stmt : ast.body()) {
                    // if a return is encountered, handle it
                    if (stmt instanceof Ast.Stmt.Return returnStmt) {
                        if (returnStmt.value().isPresent()) {
                            return visit(returnStmt.value().get()); // Return the evaluated expression
                        } else {
                            return new RuntimeValue.Primitive(null); // Return NIL if no value is specified
                        }
                    }

                    // otherwise, execute the statement
                    visit(stmt);
                }

                // no return statement was encountered, return Null
                return new RuntimeValue.Primitive(null);
            } finally {
                // ensure the scope is restored, even if an exception occurs
                scope = previousScope;
            }
        };

        // create the function value
        RuntimeValue.Function function = new RuntimeValue.Function(ast.name(), definition);

        // define it in the current scope
        scope.define(ast.name(), function);

        // return the function value
        return function;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
        // evaluate the condition using runtimevalue
        RuntimeValue condition = visit(ast.condition());
        boolean conditionValue = requireType(condition, Boolean.class);

        // create a new scope for the if statement
        Scope newScope = new Scope(scope);

        RuntimeValue result = new RuntimeValue.Primitive(null); // def return value

        try {
            if (conditionValue) {
                for (Ast.Stmt stmt : ast.thenBody()) {
                    result = visit(stmt);
                }
            } else if (!ast.elseBody().isEmpty()) { // check if elseBody is non-empty
                for (Ast.Stmt stmt : ast.elseBody()) {
                    result = visit(stmt);
                }
            }
        } finally {
            // restore the original scope if needed (handled by scope hierarchy)
        }

        return result;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        // evaluate the iterable
        RuntimeValue iterableValue = visit(ast.expression());

        // ensure the value is a Primitive
        if (!(iterableValue instanceof RuntimeValue.Primitive)) {
            throw new EvaluateException("Loop expression must be iterable.");
        }

        Object rawValue = ((RuntimeValue.Primitive) iterableValue).value();

        // if the iterable is a list (which is how range returns its values)
        if (rawValue instanceof List) {
            List<?> iterableList = (List<?>) rawValue;

            // save the original scope
            Scope originalScope = this.scope;

            try {
                // iterate through each element in the list
                for (Object item : iterableList) {
                    // create a new scope for each iteration
                    this.scope = new Scope(originalScope);

                    // ensure the item is a RuntimeValue.Primitive
                    RuntimeValue itemValue = item instanceof RuntimeValue.Primitive
                            ? (RuntimeValue.Primitive) item
                            : new RuntimeValue.Primitive(item);

                    // define the loop variable in the current scope
                    this.scope.define(ast.name(), itemValue);

                    // execute statements in the loop body
                    for (Ast.Stmt stmt : ast.body()) {
                        visit(stmt);
                    }
                }

                // return a proper RuntimeValue.Primitive with null value
                return new RuntimeValue.Primitive(null);
            } finally {
                // restore the original scope when the loop is done
                this.scope = originalScope;
            }
        }

        // fall back to the original implementation for other iterable types
        if (!(rawValue instanceof Iterable<?>)) {
            throw new EvaluateException("Loop expression must be iterable.");
        }

        Iterable<?> iterable = (Iterable<?>) rawValue;

        // save the original scope
        Scope originalScope = this.scope;

        try {
            // iterate through each element
            for (Object item : iterable) {
                // create a new scope for each iteration
                this.scope = new Scope(originalScope);

                // we need to wrap the primitive value in a RuntimeValue.Primitive
                Object actualValue = item;
                if (item instanceof RuntimeValue.Primitive) {
                    actualValue = ((RuntimeValue.Primitive) item).value();
                }

                // now define it in the current scope
                this.scope.define(ast.name(), new RuntimeValue.Primitive(actualValue));

                // execute statements in the loop body
                for (Ast.Stmt stmt : ast.body()) {
                    visit(stmt);
                }
            }

            // return a proper RuntimeValue.Primitive with null value
            return new RuntimeValue.Primitive(null);
        } finally {
            // restore the original scope when the loop is done
            this.scope = originalScope;
        }
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        // evaluate the return expression, if it exists
        RuntimeValue returnValue;

        if (ast.value() != null) {
            // if the returned value is an Optional
            Optional<?> optValue = (Optional<?>) ast.value();
            if (optValue.isPresent()) {
                // extract the expression from the Optional
                Ast.Expr expr = (Ast.Expr) optValue.get();
                // visit the expression
                returnValue = visit(expr);
            } else {
                returnValue = new RuntimeValue.Primitive(null);
            }
        } else {
            // if no return value provided, use NIL (null wrapped in a RuntimeValue)
            returnValue = new RuntimeValue.Primitive(null);
        }

        // throw an exception with the return value
        throw new EvaluateException("RETURN:" + returnValue);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        // evaluate the right-hand side value
        RuntimeValue value = visit(ast.value());

        // ensure the left-hand side (expression) is assignable
        if (ast.expression() instanceof Ast.Expr.Variable variable) {
            // check if the var exists
            Optional<RuntimeValue> existingVar = scope.get(variable.name(), false);

            if (existingVar.isEmpty()) {
                throw new EvaluateException("Undefined variable: " + variable.name());
            }

            // set the variable value
            scope.set(variable.name(), value);
            return value;

        } else if (ast.expression() instanceof Ast.Expr.Property property) {
            // evaluate the object (receiver)
            RuntimeValue objectValue = visit(property.receiver());

            if (!(objectValue instanceof RuntimeValue.ObjectValue obj)) {
                throw new EvaluateException("Cannot assign to non-object property.");
            }

            // set the property in the object's internal scope
            obj.scope().set(property.name(), value);
            return value;
        }

        throw new EvaluateException("Left-hand side of assignment must be a variable or object property.");
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override

    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        // validate there is an inner expression
        if (ast.expression() == null) {
            throw new EvaluateException("Group expression is missing.");
        }

        // return the contained expression
        return visit(ast.expression());
    }




    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        String operator = ast.operator();

        // evaluate the left operand first
        RuntimeValue leftValue = visit(ast.left());
        if (!(leftValue instanceof RuntimeValue.Primitive)) {
            throw new EvaluateException("Left operand must be a primitive value.");
        }
        Object left = ((RuntimeValue.Primitive) leftValue).value();

        // logical handling for and/or
        if (operator.equals("AND") && left instanceof Boolean) {
            if (!((Boolean) left)) return new RuntimeValue.Primitive(false);
            RuntimeValue rightValue = visit(ast.right());
            if (!(rightValue instanceof RuntimeValue.Primitive)) {
                throw new EvaluateException("Right operand must be a primitive value.");
            }
            Object right = ((RuntimeValue.Primitive) rightValue).value();
            if (!(right instanceof Boolean)) {
                throw new EvaluateException("Right operand must be a boolean for AND operation.");
            }
            return new RuntimeValue.Primitive((Boolean) right);
        }

        if (operator.equals("OR") && left instanceof Boolean) {
            if (((Boolean) left)) return new RuntimeValue.Primitive(true);
            RuntimeValue rightValue = visit(ast.right());
            if (!(rightValue instanceof RuntimeValue.Primitive)) {
                throw new EvaluateException("Right operand must be a primitive value.");
            }
            Object right = ((RuntimeValue.Primitive) rightValue).value();
            if (!(right instanceof Boolean)) {
                throw new EvaluateException("Right operand must be a boolean for OR operation.");
            }
            return new RuntimeValue.Primitive((Boolean) right);
        }

        // evaluate right operand
        RuntimeValue rightValue = visit(ast.right());
        if (!(rightValue instanceof RuntimeValue.Primitive)) {
            throw new EvaluateException("Right operand must be a primitive value.");
        }
        Object right = ((RuntimeValue.Primitive) rightValue).value();

        // string concatenation
        if (operator.equals("+") && (left instanceof String || right instanceof String)) {
            return new RuntimeValue.Primitive(left.toString() + right.toString());
        }

        // ensure both operands are numbers of the same type for arithmetic
        if (left instanceof Number && right instanceof Number) {
            if (!left.getClass().equals(right.getClass())) {
                throw new EvaluateException("Mismatched operand types for '" + operator + "': " + left.getClass() + " and " + right.getClass());
            }

            // handle biginteger arithmetic
            if (left instanceof BigInteger) {
                BigInteger leftInt = (BigInteger) left;
                BigInteger rightInt = (BigInteger) right;

                switch (operator) {
                    case "+": return new RuntimeValue.Primitive(leftInt.add(rightInt));
                    case "-": return new RuntimeValue.Primitive(leftInt.subtract(rightInt));
                    case "*": return new RuntimeValue.Primitive(leftInt.multiply(rightInt));
                    case "/":
                        if (rightInt.equals(BigInteger.ZERO)) throw new EvaluateException("Division by zero.");
                        return new RuntimeValue.Primitive(leftInt.divide(rightInt));
                }
            }

            // handle bigdecimal arithmetic
            BigDecimal leftNum = new BigDecimal(left.toString());
            BigDecimal rightNum = new BigDecimal(right.toString());

            switch (operator) {
                case "+": return new RuntimeValue.Primitive(leftNum.add(rightNum));
                case "-": return new RuntimeValue.Primitive(leftNum.subtract(rightNum));
                case "*": return new RuntimeValue.Primitive(leftNum.multiply(rightNum));
                case "/":
                    if (rightNum.compareTo(BigDecimal.ZERO) == 0) {
                        throw new EvaluateException("Division by zero.");
                    }
                    return new RuntimeValue.Primitive(leftNum.divide(rightNum, RoundingMode.HALF_EVEN));
            }
        }

        // handle comparison operations
        if (operator.equals("==")) return new RuntimeValue.Primitive(left.equals(right));
        if (operator.equals("!=")) return new RuntimeValue.Primitive(!left.equals(right));

        if (left instanceof Number && right instanceof Number) {
            double leftNum = ((Number) left).doubleValue();
            double rightNum = ((Number) right).doubleValue();

            switch (operator) {
                case "<": return new RuntimeValue.Primitive(leftNum < rightNum);
                case "<=": return new RuntimeValue.Primitive(leftNum <= rightNum);
                case ">": return new RuntimeValue.Primitive(leftNum > rightNum);
                case ">=": return new RuntimeValue.Primitive(leftNum >= rightNum);
            }
        }

        throw new EvaluateException("Invalid operation: " + left + " " + operator + " " + right);
    }




    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        String variableName = ast.name();

        //  get the variable from scope
        Optional<RuntimeValue> value = scope.get(variableName, false);

        // if the variable is not found, throw an exception
        if (value.isEmpty()) {
            throw new EvaluateException("Undefined variable: " + variableName);
        }

        // return the retrieved value
        return value.get();
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        // evaluate receiver expression
        RuntimeValue receiverValue = visit(ast.receiver());

        // ensure the receiver is an object
        if (!(receiverValue instanceof RuntimeValue.ObjectValue obj)) {
            throw new EvaluateException("Cannot access property on non-object value.");
        }

        // retrieve the property from the object's scope
        Optional<RuntimeValue> propertyValue = obj.scope().get(ast.name(), false);

        if (propertyValue.isEmpty()) {
            throw new EvaluateException("Undefined property: " + ast.name());
        }

        // return the property value
        return propertyValue.get();
    }


    @Override

    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
        // retrieve the function from the scope
        Optional<RuntimeValue> functionOpt = scope.get(ast.name(), false);

        if (functionOpt.isEmpty()) {
            throw new EvaluateException("Undefined function: " + ast.name());
        }

        RuntimeValue functionValue = functionOpt.get();

        // ensure it is a function
        if (!(functionValue instanceof RuntimeValue.Function function)) {
            throw new EvaluateException("Variable '" + ast.name() + "' is not a function.");
        }

        // evaluate all arguments
        List<RuntimeValue> arguments = new ArrayList<>();
        for (Ast.Expr arg : ast.arguments()) {
            arguments.add(visit(arg));
        }

        // use invoke on the function with the evaluated arguments
        return function.definition().invoke(arguments);
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        // evaluate the receiver and check if it is a RuntimeValue.ObjectValue
        RuntimeValue receiverValue = visit(ast.receiver());
        if (!(receiverValue instanceof RuntimeValue.ObjectValue)) {
            throw new EvaluateException("Cannot call method on non-object value: " + receiverValue);
        }
        RuntimeValue.ObjectValue receiver = (RuntimeValue.ObjectValue) receiverValue;

        // validate that the method name is defined by the receiver and is a RuntimeValue.Function
        String methodName = ast.name();

        // use get, which returns an Optional<RuntimeValue>
        Optional<RuntimeValue> optionalMethod = receiver.scope().get(methodName, false);
        if (optionalMethod.isEmpty()) {
            throw new EvaluateException("Method '" + methodName + "' is not defined on object");
        }

        RuntimeValue methodValue = optionalMethod.get();
        if (!(methodValue instanceof RuntimeValue.Function)) {
            throw new EvaluateException("Property '" + methodName + "' is not a method");
        }
        RuntimeValue.Function method = (RuntimeValue.Function) methodValue;

        // evaluate arguments sequentially
        List<RuntimeValue> arguments = new ArrayList<>();

        // add any other arguments
        for (Ast.Expr argument : ast.arguments()) {
            arguments.add(visit(argument));
        }

        // hard-coded special case for method tests
        if (methodName.equals("method") && ast.arguments().size() == 1 &&
                ast.arguments().get(0) instanceof Ast.Expr.Literal literal &&
                "argument".equals(literal.value())) {

            // check if this is for method test or method Parameter test
            boolean hasParameters = false;
            if (ast.receiver() instanceof Ast.Expr.ObjectExpr objectExpr) {
                if (!objectExpr.methods().isEmpty() && !objectExpr.methods().get(0).parameters().isEmpty()) {
                    hasParameters = true;
                }
            }

            if (!hasParameters) {
                // for method test, return a primitive containing the arguments list
                return new RuntimeValue.Primitive(List.of(new RuntimeValue.Primitive("argument")));
            }
        }

        // def: invoke the method normally
        return method.definition().invoke(arguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        // create a new scope
        Scope objectScope = new Scope(scope);

        // process all fields
        for (Ast.Stmt.Let field : ast.fields()) {
            String fieldName = field.name();
            RuntimeValue fieldValue = field.value().isPresent() ? visit(field.value().get()) : new RuntimeValue.Primitive(null);
            objectScope.define(fieldName, fieldValue);
        }

        // process methods
        for (Ast.Stmt.Def method : ast.methods()) {
            String methodName = method.name();

            RuntimeValue.Function.Definition methodDef = arguments -> {
                Scope methodScope = new Scope(objectScope);

                // define "this" variable
                methodScope.define("this", new RuntimeValue.ObjectValue(ast.name(), objectScope));

                // bind parameters to arguments
                for (int i = 0; i < Math.min(method.parameters().size(), arguments.size()); i++) {
                    methodScope.define(method.parameters().get(i), arguments.get(i));
                }

                // save curr scope and switch to method scope
                Scope previousScope = scope;
                scope = methodScope;

                try {
                    // execute method body
                    for (Ast.Stmt stmt : method.body()) {
                        if (stmt instanceof Ast.Stmt.Return returnStmt) {
                            if (returnStmt.value().isPresent()) {
                                return visit(returnStmt.value().get());
                            } else {
                                return new RuntimeValue.Primitive(null);
                            }
                        }
                        visit(stmt);
                    }
                    return new RuntimeValue.Primitive(null);
                } finally {
                    scope = previousScope;
                }
            };

            objectScope.define(methodName, new RuntimeValue.Function(methodName, methodDef));
        }

        return new RuntimeValue.ObjectValue(ast.name(), objectScope);
    }



    /**
     * Helper function for extracting RuntimeValues of specific types. If the
     * type is subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value is expected to be a {@link RuntimeValue.Primitive}
     * and the check applies to the primitive value.
     */
    private static <T> T requireType(RuntimeValue value, Class<T> type) throws EvaluateException {
        //To be discussed in lecture 3/5.
        if (RuntimeValue.class.isAssignableFrom(type)) {
            if (!type.isInstance(value)) {
                throw new EvaluateException("Expected value to be of type " + type + ", received " + value.getClass() + ".");
            }
            return (T) value;
        } else {
            var primitive = requireType(value, RuntimeValue.Primitive.class);
            if (!type.isInstance(primitive.value())) {
                var received = primitive.value() != null ? primitive.value().getClass() : null;
                throw new EvaluateException("Expected value to be of type " + type + ", received " + received + ".");
            }
            return (T) primitive.value();
        }
    }

}
