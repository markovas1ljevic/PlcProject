package plc.project.generator;

import plc.project.analyzer.Ir;
import plc.project.analyzer.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
//import java.math.RoundingMode;
//import java.util.Objects;

public class Generator implements Ir.Visitor<StringBuilder, RuntimeException> {

    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    private void newline(int indent) {
        builder.append("\n");
        builder.append("    ".repeat(indent));
    }

    @Override
    public StringBuilder visit(Ir.Source ir) {
        builder.append(Environment.imports()).append("\n\n");
        builder.append("public final class Main {").append("\n\n");
        builder.append(Environment.definitions()).append("\n");
        //Java doesn't allow for nested functions, but we will pretend it does.
        //To support simple programs involving functions, we will "hoist" any
        //variable/function declaration at the start of the program to allow
        //these functions to be used as valid Java.
        indent = 1;
        boolean main = false;
        for (var statement : ir.statements()) {
            newline(indent);
            if (!main) {
                if (statement instanceof Ir.Stmt.Let || statement instanceof Ir.Stmt.Def) {
                    builder.append("static ");
                } else {
                    builder.append("public static void main(String[] args) {");
                    main = true;
                    indent = 2;
                    newline(indent);
                }
            }
            visit(statement);
        }
        if (main) {
            builder.append("\n").append("    }");
        }
        indent = 0;
        builder.append("\n\n").append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Let ir) {
        if (ir.type() instanceof Type.Object) {
            builder.append("var ");
            builder.append(ir.name());
        } else {
            builder.append(ir.type().jvmName());
            builder.append(" ");
            builder.append(ir.name());
        }

        if (ir.value().isPresent()) {
            builder.append(" = ");
            visit(ir.value().get());
        }

        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Def ir) {

        builder.append(ir.returns().jvmName());
        builder.append(" ");
        builder.append(ir.name());
        builder.append("(");


        boolean first = true;
        for (var parameter : ir.parameters()) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(parameter.type().jvmName());
            builder.append(" ");
            builder.append(parameter.name());
            first = false;
        }

        builder.append(") {");
        indent++;


        for (var statement : ir.body()) {
            newline(indent);
            visit(statement);
        }

        indent--;
        newline(indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {
        builder.append("if (");
        visit(ir.condition());
        builder.append(") {");

        indent++;
        for (var statement : ir.thenBody()) {
            newline(indent);
            visit(statement);
        }
        indent--;

        if (!ir.elseBody().isEmpty()) {
            newline(indent);
            builder.append("} else {");

            indent++;
            for (var statement : ir.elseBody()) {
                newline(indent);
                visit(statement);
            }
            indent--;
        }

        newline(indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.For ir) {
        builder.append("for (");
        builder.append(ir.type().jvmName());
        builder.append(" ");
        builder.append(ir.name());
        builder.append(" : ");
        visit(ir.expression());
        builder.append(") {");

        indent++;
        for (var statement : ir.body()) {
            newline(indent);
            visit(statement);
        }
        indent--;

        newline(indent);
        builder.append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Return ir) {
        builder.append("return");

        if (ir.value().isPresent()) {
            builder.append(" ");
            visit(ir.value().get());
        } else {
            builder.append(" null");
        }

        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Expression ir) {
        visit(ir.expression());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Variable ir) {
        builder.append(ir.variable().name());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Property ir) {
        visit(ir.property().receiver());
        builder.append(".");
        builder.append(ir.property().name());
        builder.append(" = ");
        visit(ir.value());
        builder.append(";");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Literal ir) {
        var literal = switch (ir.value()) {
            case null -> "null";
            case Boolean b -> b.toString();
            case BigInteger i -> "new BigInteger(\"" + i + "\")";
            case BigDecimal d -> "new BigDecimal(\"" + d + "\")";
            case String s -> "\"" + s + "\""; //TODO: Escape characters?
            //If the IR value isn't one of the above types, the Parser/Analyzer
            //is returning an incorrect IR - this is an implementation issue,
            //hence throw AssertionError rather than a "standard" exception.
            default -> throw new AssertionError(ir.value().getClass());
        };
        builder.append(literal);
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Group ir) {
        builder.append("(");
        visit(ir.expression());
        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Binary ir) {
        String operator = ir.operator();

        if (operator.equals("+")) {
            if (ir.type().equals(Type.STRING)) {
                visit(ir.left());
                builder.append(" + ");
                visit(ir.right());
            } else {
                builder.append("(");
                visit(ir.left());
                builder.append(").add(");
                visit(ir.right());
                builder.append(")");
            }
        }
        else if (operator.equals("-")) {
            builder.append("(");
            visit(ir.left());
            builder.append(").subtract(");
            visit(ir.right());
            builder.append(")");
        }
        else if (operator.equals("*")) {
            builder.append("(");
            visit(ir.left());
            builder.append(").multiply(");
            visit(ir.right());
            builder.append(")");
        }
        else if (operator.equals("/")) {
            builder.append("(");
            visit(ir.left());
            builder.append(").divide(");
            visit(ir.right());
            if (ir.type().equals(Type.DECIMAL)) {
                builder.append(", RoundingMode.HALF_EVEN");
            }
            builder.append(")");
        }
        else if (operator.equals("<") || operator.equals("<=") || operator.equals(">") || operator.equals(">=")) {
            builder.append("(");
            visit(ir.left());
            builder.append(").compareTo(");
            visit(ir.right());
            builder.append(") ");
            builder.append(operator);
            builder.append(" 0");
        }
        else if (operator.equals("==")) {
            builder.append("Objects.equals(");
            visit(ir.left());
            builder.append(", ");
            visit(ir.right());
            builder.append(")");
        }
        else if (operator.equals("!=")) {
            builder.append("!Objects.equals(");
            visit(ir.left());
            builder.append(", ");
            visit(ir.right());
            builder.append(")");
        }
        else if (operator.equals("AND")) {
            if (ir.left() instanceof Ir.Expr.Binary leftBinary && leftBinary.operator().equals("OR")) {
                builder.append("(");
                visit(ir.left());
                builder.append(")");
            } else {
                visit(ir.left());
            }
            builder.append(" && ");
            visit(ir.right());
        }
        else if (operator.equals("OR")) {
            visit(ir.left());
            builder.append(" || ");
            visit(ir.right());
        }

        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Variable ir) {
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Property ir) {
        visit(ir.receiver());
        builder.append(".");
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Function ir) {
        builder.append(ir.name());
        builder.append("(");

        boolean first = true;
        for (var argument : ir.arguments()) {
            if (!first) {
                builder.append(", ");
            }
            visit(argument);
            first = false;
        }

        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Method ir) {
        visit(ir.receiver());
        builder.append(".");
        builder.append(ir.name());
        builder.append("(");

        boolean first = true;
        for (var argument : ir.arguments()) {
            if (!first) {
                builder.append(", ");
            }
            visit(argument);
            first = false;
        }

        builder.append(")");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.ObjectExpr ir) {
        builder.append("new Object() {");
        indent++;


        for (var field : ir.fields()) {
            newline(indent);
            visit(field);
        }


        for (var method : ir.methods()) {
            newline(indent);
            visit(method);
        }

        indent--;
        newline(indent);
        builder.append("}");
        return builder;
    }

}
