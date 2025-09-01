package plc.project.parser;

import plc.project.lexer.Token;

import java.util.List;

import java.util.ArrayList;

import java.math.BigDecimal;

import java.util.Optional;

import java.math.BigInteger;

import static com.google.common.base.Preconditions.checkState;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast.Source parseSource() throws ParseException {
        List<Ast.Stmt> statements = new ArrayList<>(); // create a list to store statements

        // parse until token stream is empty
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }

        return new Ast.Source(statements); //TODO
    }

    public Ast.Stmt parseStmt() throws ParseException {
        if (tokens.peek("LET")) {
            return parseLetStmt();
        } else if (tokens.peek("DEF")) {
            return parseDefStmt();
        } else if (tokens.peek("IF")) {
            return parseIfStmt();
        } else if (tokens.peek("FOR")) {
            return parseForStmt();
        } else if (tokens.peek("RETURN")) {
            return parseReturnStmt();
        } else {
            return parseExpressionOrAssignmentStmt();
        }
    }


    private Ast.Stmt.Let parseLetStmt() throws ParseException {
        // check for let token
        if (!tokens.match("LET")) {
            throw new ParseException("Expected 'LET'");
        }

        // check for var name
        if (!tokens.match(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'LET'");
        }
        String name = tokens.get(-1).literal();

        // new  check for optional type annotation per spec
        Optional<String> typeName = Optional.empty();
        if (tokens.match(":")) {
            if (!tokens.match(Token.Type.IDENTIFIER)) {
                throw new ParseException("Expected type name after ':'");
            }
            typeName = Optional.of(tokens.get(-1).literal());
        }

        // opt initialization
        Optional<Ast.Expr> value = Optional.empty();
        if (tokens.match("=")) {
            value = Optional.of(parseExpr());
        }

        // ensure statement ends with semicolon
        if (!tokens.match(";")) {
            throw new ParseException("Expected ';' after let statement");
        }

        // Updated return statement with type parameter
        return new Ast.Stmt.Let(name, typeName, value);
    }




    private Ast.Stmt.Def parseDefStmt() throws ParseException {
        if (!tokens.match("DEF")) {
            throw new ParseException("Expected 'DEF'");
        }
        if (!tokens.match(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'DEF'");
        }
        String name = tokens.get(-1).literal();

        if (!tokens.match("(")) {
            throw new ParseException("Expected '(' after function name");
        }

        List<String> parameters = new ArrayList<>();
        if (!tokens.match(")")) {
            do {
                if (!tokens.match(Token.Type.IDENTIFIER)) {
                    throw new ParseException("Expected parameter name");
                }
                parameters.add(tokens.get(-1).literal());
            } while (tokens.match(","));
            if (!tokens.match(")")) {
                throw new ParseException("Expected ')' after parameters");
            }
        }

        if (!tokens.match("DO")) {
            throw new ParseException("Expected 'DO' after function parameters");
        }

        List<Ast.Stmt> statements = new ArrayList<>();
        while (!tokens.match("END")) {
            statements.add(parseStmt());
        }

        return new Ast.Stmt.Def(name, parameters, statements);
    }

    private Ast.Stmt.If parseIfStmt() throws ParseException {
        // verify "IF" gets matched
        if (!tokens.match("IF")) {
            throw new ParseException("Expected 'IF'");
        }

        // parse the condition expression
        Ast.Expr condition = parseExpr();

        // ensure "DO" follows the condition
        if (!tokens.match("DO")) {
            throw new ParseException("Expected 'DO' after condition");
        }

        // parse "then" statements until "ELSE" or "END" is revealed
        List<Ast.Stmt> thenStatements = new ArrayList<>();
        while (!tokens.peek("ELSE") && !tokens.peek("END")) {
            thenStatements.add(parseStmt());
        }

        // parse 'ELSE' if it is present
        List<Ast.Stmt> elseStatements = new ArrayList<>();
        if (tokens.match("ELSE")) {
            while (!tokens.peek("END")) {
                elseStatements.add(parseStmt());
            }
        }

        // ensure the statement is properly closed with 'END'
        if (!tokens.match("END")) {
            throw new ParseException("Expected 'END' after if statement");
        }

        // return the constructed if statement
        return new Ast.Stmt.If(condition, thenStatements, elseStatements);
    }


    private Ast.Stmt.For parseForStmt() throws ParseException {
        // match keyword "FOR"
        if (!tokens.match("FOR")) {
            throw new ParseException("Expected 'FOR'");
        }

        // match identifier following for
        if (!tokens.match(Token.Type.IDENTIFIER)) {
            throw new ParseException("Expected identifier after 'FOR'");
        }
        String name = tokens.get(-1).literal();

        // verify "IN" is present
        if (!tokens.match("IN")) {
            throw new ParseException("Expected 'IN' after variable name");
        }

        // parse the iterable expression
        Ast.Expr iterable = parseExpr();

        // match "DO" keyword
        if (!tokens.match("DO")) {
            throw new ParseException("Expected 'DO' after iterable");
        }

        // parse statements within the for loop
        List<Ast.Stmt> statements = new ArrayList<>();
        while (!tokens.peek("END")) {
            statements.add(parseStmt());
        }

        // ensure "END" keyword is present to close the for loop
        if (!tokens.match("END")) {
            throw new ParseException("Expected 'END' after for loop");
        }

        // return the for statement
        return new Ast.Stmt.For(name, iterable, statements);
    }


    private Ast.Stmt.Return parseReturnStmt() throws ParseException {
        // match "RETURN"
        if (!tokens.match("RETURN")) {
            throw new ParseException("Expected 'RETURN'");
        }

        // parse the optional return value
        Ast.Expr value = null;
        if (!tokens.match(";")) {
            value = parseExpr();

            // ensure a statement ends with a semicolon
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';' after return value");
            }
        }

        // return the statement with the optional value
        return new Ast.Stmt.Return(value != null ? Optional.of(value) : Optional.empty());
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        Ast.Expr expr = parseExpr();

        if (tokens.match("=")) {
            Ast.Expr value = parseExpr();
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';' after assignment");
            }
            return new Ast.Stmt.Assignment(expr, value);
        } else {
            if (!tokens.match(";")) {
                throw new ParseException("Expected ';' after expression");
            }
            return new Ast.Stmt.Expression(expr);
        }
    }


    public Ast.Expr parseExpr() throws ParseException {
        // return logical expression
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        Ast.Expr expr = parseComparisonExpr();
        while (tokens.peek("AND") || tokens.peek("OR")) {
            String operator = tokens.get(0).literal();
            tokens.match(operator);
            Ast.Expr right = parseComparisonExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        Ast.Expr expr = parseAdditiveExpr();
        while (tokens.peek("==") || tokens.peek("!=") || tokens.peek("<") || tokens.peek(">") || tokens.peek("<=") || tokens.peek(">=")) {
            String operator = tokens.get(0).literal();
            tokens.match(operator);
            Ast.Expr right = parseAdditiveExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        Ast.Expr expr = parseMultiplicativeExpr();
        while (tokens.peek("+") || tokens.peek("-")) {
            String operator = tokens.get(0).literal();
            tokens.match(operator);
            Ast.Expr right = parseMultiplicativeExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        Ast.Expr expr = parseSecondaryExpr();
        while (tokens.peek("*") || tokens.peek("/")) {
            String operator = tokens.get(0).literal();
            tokens.match(operator);
            Ast.Expr right = parseSecondaryExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        Ast.Expr expr = parsePrimaryExpr();

        while (tokens.match(".")) {
            if (tokens.match(Token.Type.IDENTIFIER)) {
                String name = tokens.get(-1).literal();
                if (tokens.match("(")) { // use match for parentheses
                    List<Ast.Expr> arguments = new ArrayList<>();
                    if (!tokens.peek(")")) { // check for arguments
                        do {
                            arguments.add(parseExpr());
                        } while (tokens.match(","));
                    }
                    tokens.match(")");
                    expr = new Ast.Expr.Method(expr, name, arguments); // create method
                } else { // access the property
                    expr = new Ast.Expr.Property(expr, name);
                }
            } else {
                throw new ParseException("Expected identifier after '.'");
            }
        }
        return expr;
    }




    private Ast.Expr parsePrimaryExpr() throws ParseException {
        if (tokens.peek(Token.Type.INTEGER)) {
            // parse int literal
            BigInteger value = new BigInteger(tokens.get(0).literal());
            tokens.match(Token.Type.INTEGER);
            return new Ast.Expr.Literal(value);
        }
        else if (tokens.peek(Token.Type.DECIMAL)) {
            // parse decimal
            BigDecimal value = new BigDecimal(tokens.get(0).literal());
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(value);
        }
        else if (tokens.peek(Token.Type.STRING)) {
            // parse string literal with proper escaping
            String literal = tokens.get(0).literal();
            tokens.match(Token.Type.STRING);

            // remove string's surrounding quotes
            if (literal.length() >= 2 && literal.startsWith("\"") && literal.endsWith("\"")) {
                literal = literal.substring(1, literal.length() - 1);
            } else {
                throw new ParseException("Invalid string literal format.");
            }

            // handle escape sequences
            literal = literal.replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\\\", "\\");

            return new Ast.Expr.Literal(literal);
        }
        else if (tokens.peek(Token.Type.CHARACTER)) {
            // parse char literal
            String literal = tokens.get(0).literal();
            tokens.match(Token.Type.CHARACTER);

            if (literal.length() == 3 && literal.startsWith("'") && literal.endsWith("'")) {
                return new Ast.Expr.Literal(literal.charAt(1));
            } else {
                throw new ParseException("Invalid character literal format.");
            }
        }
        else if (tokens.peek(Token.Type.IDENTIFIER) && tokens.peek("TRUE")) {
            // parse true
           // tokens.match(Token.Type.IDENTIFIER);
            return parseLiteralExpr();
        }
        else if (tokens.peek(Token.Type.IDENTIFIER) && tokens.peek( "FALSE")) {
            // parse false
            return parseLiteralExpr();
        }

        else if (tokens.peek("(")) {
            // parse grouped expression
            return parseGroupExpr();
        }
        else if (tokens.peek("OBJECT")) {
            // parse object expression
            return parseObjectExpr();
        }

        else if (tokens.peek(Token.Type.IDENTIFIER) && !tokens.peek("NIL")) {
            // parse variable or function
            return parseVariableOrFunctionExpr();
        }

        else if (tokens.peek("NIL")){ // parse NIL
            //tokens.match("NIL");
            return parseLiteralExpr();
        }

        throw new ParseException("Invalid primary expression.");
    }



    private Ast.Expr.Literal parseLiteralExpr() throws ParseException {
        if (tokens.peek(Token.Type.INTEGER)) {
            String literal = tokens.get(0).literal();
            tokens.match(Token.Type.INTEGER);
            return new Ast.Expr.Literal(new BigInteger(literal));
        }
        else if (tokens.peek(Token.Type.DECIMAL)) {
            String literal = tokens.get(0).literal();
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(new BigDecimal(literal));
        }

        else if (tokens.peek(Token.Type.CHARACTER)) {
            String literal = tokens.get(0).literal();
            tokens.match(Token.Type.CHARACTER);

            // ensure character is extracted
            if (literal.length() == 3 && literal.startsWith("'") && literal.endsWith("'")) {
                return new Ast.Expr.Literal(literal.charAt(1));
            } else {
                throw new ParseException("Invalid character literal format.");
            }
        }
        else if (tokens.peek(Token.Type.IDENTIFIER) && tokens.peek("TRUE")) {
            tokens.match(Token.Type.IDENTIFIER);
            return new Ast.Expr.Literal(true);
        }
        else if (tokens.peek(Token.Type.IDENTIFIER) && tokens.peek( "FALSE")) {
            tokens.match(Token.Type.IDENTIFIER);
            return new Ast.Expr.Literal(false);
        }
        else if (tokens.peek(Token.Type.IDENTIFIER) && tokens.peek("NIL")) {
            tokens.match(Token.Type.IDENTIFIER);
            return new Ast.Expr.Literal(null);  // Ensure NIL is treated as null
        }
        throw new ParseException("Invalid literal expression.");
    }




    private Ast.Expr.Group parseGroupExpr() throws ParseException {
        if (tokens.match("(")) {
            Ast.Expr expr = parseExpr();
            if (tokens.match(")")) {
                return new Ast.Expr.Group(expr);
            }
        }
        throw new ParseException("Invalid group expression");
    }

    private Ast.Expr.ObjectExpr parseObjectExpr() throws ParseException {
        // match 'OBJECT' keyword
        if (!tokens.match("OBJECT")) {
            throw new ParseException("Expected 'OBJECT'");
        }

        // parse the optional identifier
        Optional<String> name = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER) && !tokens.peek("DO")) {
            name = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER); // Consume the identifier properly
        }

        // match the 'DO' keyword
        if (!tokens.match("DO")) {
            throw new ParseException("Expected 'DO' after object name, but found: " + tokens.get(0));
        }

        // parse let and def statements until 'END' is encountered
        List<Ast.Stmt.Let> letStatements = new ArrayList<>();
        List<Ast.Stmt.Def> defStatements = new ArrayList<>();

        while (!tokens.peek("END")) {
            if (tokens.peek("LET")) {
                letStatements.add(parseLetStmt());
            } else if (tokens.peek("DEF")) {
                defStatements.add(parseDefStmt());
            } else {
                throw new ParseException("Expected 'LET', 'DEF', or 'END' inside object body, but found: " + tokens.get(0));
            }
        }

        // match the 'END' token
        if (!tokens.match("END")) {
            throw new ParseException("Expected 'END' after object body");
        }

        // return the constructed ObjectExpr
        return new Ast.Expr.ObjectExpr(name, letStatements, defStatements);
    }




    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            String name = tokens.get(0).literal();
            tokens.match(name);
            if (tokens.match("(")) {
                List<Ast.Expr> arguments = new ArrayList<>();
                while (!tokens.match(")")) {
                    arguments.add(parseExpr());
                    tokens.match(",");
                }
                return new Ast.Expr.Function(name, arguments);
            }
            return new Ast.Expr.Variable(name);
        }
        throw new ParseException("Invalid variable or function expression");
    }


    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
