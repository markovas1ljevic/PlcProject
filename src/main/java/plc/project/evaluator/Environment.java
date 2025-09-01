package plc.project.evaluator;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.math.BigInteger;

public final class Environment {

    public static Scope scope() {
        var scope = new Scope(null);
        //"Native" functions for printing and creating lists.
        scope.define("debug", new RuntimeValue.Function("debug", Environment::debug));
        scope.define("print", new RuntimeValue.Function("print", Environment::print));
        scope.define("log", new RuntimeValue.Function("log", Environment::log));
        scope.define("list", new RuntimeValue.Function("list", Environment::list));
        scope.define("range", new RuntimeValue.Function("range", Environment::range));
        //Helper functions for testing variables, functions, and objects.
        scope.define("variable", new RuntimeValue.Primitive("variable"));
        scope.define("function", new RuntimeValue.Function("function", Environment::function));
        var object = new RuntimeValue.ObjectValue(Optional.of("Object"), new Scope(null));
        scope.define("object", object);
        object.scope().define("property", new RuntimeValue.Primitive("property"));
        object.scope().define("method", new RuntimeValue.Function("method", Environment::method));
        return scope;
    }

    /**
     * Prints the raw RuntimeValue.toString() result.
     */
    private static RuntimeValue debug(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected debug to be called with 1 argument.");
        }
        System.out.println(arguments.getFirst());
        return new RuntimeValue.Primitive(null);
    }

    /**
     * Prints a formatted RuntimeValue.
     */
    private static RuntimeValue print(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected print to be called with 1 argument.");
        }
        System.out.println(arguments.getFirst().print());
        return new RuntimeValue.Primitive(null);
    }

    /**
     * Prints a formatted RuntimeValue and returns it.
     */
    static RuntimeValue log(List<RuntimeValue> arguments) throws EvaluateException {
        if (arguments.size() != 1) {
            throw new EvaluateException("Expected log to be called with 1 argument.");
        }
        System.out.println("log: " + arguments.getFirst().print());
        return arguments.getFirst(); //size validated by print
    }

    /**
     * Returns a List value containing all arguments.
     */
    private static RuntimeValue list(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments);
    }

    /**
     * Takes two integer arguments (start, end) and returns a List containing
     * all integers in that range (inclusive, exclusive).
     */
    private static RuntimeValue range(List<RuntimeValue> arguments) throws EvaluateException {
        // check argument count
        if (arguments.size() != 2) {
            throw new EvaluateException("Range function requires exactly 2 arguments");
        }

        // extract start/end values
        RuntimeValue startArg = arguments.get(0);
        RuntimeValue endArg = arguments.get(1);

        // ensure both arguments are primitive values of bigint
        if (!(startArg instanceof RuntimeValue.Primitive) ||
                !(endArg instanceof RuntimeValue.Primitive)) {
            throw new EvaluateException("Range arguments must be primitive values");
        }

        // extract raw values
        Object startRaw = ((RuntimeValue.Primitive) startArg).value();
        Object endRaw = ((RuntimeValue.Primitive) endArg).value();

        // verify both are BigIntegers
        if (!(startRaw instanceof BigInteger) || !(endRaw instanceof BigInteger)) {
            throw new EvaluateException("Range arguments must be BigIntegers");
        }

        BigInteger start = (BigInteger) startRaw;
        BigInteger end = (BigInteger) endRaw;

        // check start <= end constraint
        if (start.compareTo(end) > 0) {
            throw new EvaluateException("Start value must be less than or equal to end value");
        }

        // create a list storing range values
        List<RuntimeValue> rangeValues = new ArrayList<>();

        // generate range values
        BigInteger current = start;
        while (current.compareTo(end) < 0) {
            rangeValues.add(new RuntimeValue.Primitive(current));
            current = current.add(BigInteger.ONE);
        }

        return new RuntimeValue.Primitive(rangeValues);
    }

    /**
     * Returns a list of all function arguments.
     */
    private static RuntimeValue function(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments);
    }

    /**
     * Returns a list of all method arguments. Question: why the difference?
     */
    private static RuntimeValue method(List<RuntimeValue> arguments) {
        return new RuntimeValue.Primitive(arguments.subList(1, arguments.size()));
    }

}
