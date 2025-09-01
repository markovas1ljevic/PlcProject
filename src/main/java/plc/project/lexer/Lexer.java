package plc.project.lexer;

import java.util.ArrayList;
import java.util.List;



public final class Lexer {

    private final CharStream chars;

    public Lexer(String input) {
        chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        List<Token> tokens = new ArrayList<>();
        if (chars.peek("[//]")) {
            return tokens;}
        if (chars.peek("[-]")) {
            throw new LexException("Invalid identifier: must start with a letter or underscore");
        }
        while (chars.has(0)) {
            if (chars.peek("[ \b\n\r\t]")) {
                chars.match("[ \b\n\r\t]");
                continue;
            }
            Token token = lexToken();
            if (token != null) {
                tokens.add(token);
            }
        }
        //if (tokens.get(0).type() == Token.Type.INTEGER && tokens.get(1).type() == Token.Type.IDENTIFIER )
        return tokens;
    }

    private void lexComment() throws LexException {
        // match "//"
        if (chars.peek("/", "/")) {
            lexComment();

        }

        // match until the end of the line
        while (chars.has(0) && !chars.peek("\n", "\r")) {
            chars.match(".");  // Consume all characters in the comment
        }

        // handle escape variations
        if (chars.has(0) && chars.peek("\r")) {
            chars.match("\r");
            if (chars.has(0) && chars.peek("\n")) {
                chars.match("\n");
            }
        } else if (chars.has(0) && chars.peek("\n")) {
            chars.match("\n");
        }
        }


    private Token lexToken() throws LexException {
     if (chars.peek("[a-zA-Z_]")) {  // identifiers start with a letter or underscore
            return lexIdentifier();
        } else if (chars.peek("[0-9]")) {  // numbers start with a digit
            return lexNumber();
        } else if (chars.peek("'")) {  // character literals start with '
            return lexCharacter();
        } else if (chars.peek("\"")) {  // string literals start with "
            return lexString();
        } else if (chars.peek("[+\\-*/=<>(){};,.]")) {  // operators
            return lexOperator();
        } else {
            throw new LexException("Unexpected character: " + chars.emit());
        }
    }


    private Token lexIdentifier() throws LexException {


        // ensure the initial character is a valid identifier start
        if (!chars.peek("[A-Za-z_]")) {
            throw new LexException("Invalid identifier: must start with a letter or underscore");

        }

        // match the first valid character
        chars.match("[A-Za-z_]");

        // match subsequent characters (letters, digits, underscores, but NO leading hyphens)
        while (chars.has(0) && chars.peek("[A-Za-z0-9_]")) {
            chars.match("[A-Za-z0-9_]");
        }
       String literal = chars.emit();
        // clean up spaces from the literal
        literal = literal.replace(" ", "");
        // Emit the token
        return new Token(Token.Type.IDENTIFIER, literal);
    }


    private Token lexNumber() throws LexException {
        // match optional sign
        if (chars.peek("[+\\-]")) {
            chars.match("[+\\-]");
        }

        // match integer part
        String literal = "";
        boolean isDecimal = false;
        while (chars.has(0) && (chars.peek("[0-9]") || chars.peek("\\.") || chars.peek("[e]"))) {
            if (!chars.match("[0-9]+")) {
                throw new LexException("Invalid number: expected at least one digit");
            }

            // match decimal part

            if (chars.peek("\\.")) {
                isDecimal = true;
                chars.match("\\.");
                if (!chars.match("[0-9]+")) {
                    throw new LexException("Invalid number: expected digits after decimal point");
                }
            }
            // optional exponent part
            if (chars.peek("[e]")) {
                chars.match("[e]"); // Match 'e'
                if (!chars.match("[0-9]+")) {
                    throw new LexException("Invalid number: expected digits in exponent");
                }

            }
            // Emit token as INTEGER or DECIMAL based on the parsed number
            literal = literal + chars.emit();
            int x = 1;
        }
        // Remove spaces from the literal
        literal = literal.replace(" ", "");
        if (isDecimal) {
            return new Token(Token.Type.DECIMAL, literal);
        } else {
            return new Token(Token.Type.INTEGER, literal);
        }
    }

       private String lexEscape() throws LexException {
        if (!chars.match("\\\\")) {
            throw new LexException("Invalid escape sequence");
        }

        if (chars.match("[btnr'\"\\\\]")) {
            return  chars.emit();  // return the escape sequence
        }

        throw new LexException("Unknown escape sequence");
    }
    //lex escape for strings
    private String lexEscapeString() throws LexException {

        if (!chars.has(0)) {
            throw new LexException("Incomplete escape sequence");
        }

        // match the backslash
        chars.match("\\\\");

        // check for valid escape sequences
      if (chars.match("n")) {
            return "\n";  // return newline character
        } else if (chars.match("t")) {
            return "\t";  // return tab
        } else if (chars.match("r")) {
            return "\r";  // return carriage return
        } else if (chars.match("\"")) {
            return "\"";  // return double quote
        } else if (chars.match("'")) {
            return "'";   // return single quote
        } else if (chars.match("\\\\")) {
            return "\\";  // return backslash

        }
        else {
            throw new LexException("Unknown escape sequence: \\" + chars.peek());
        }
    }
    private Token lexString() throws LexException {
        // ensure string starts with a double quote
        if (!chars.match("\"")) {
            throw new LexException("String literal must start with a double quote");
        }

        // read characters until the closing quotes
        while (chars.has(0) && !chars.peek("\"")) {
            if (chars.peek("\\\\")) {  // Detect escape sequence
                chars.match("\\\\");   // Consume the backslash
                lexEscapeString();           // Handle the escape sequence
            } else {
                chars.match(".");      // Consume regular character
            }
        }

        // ensure the string closes properly
        if (!chars.match("\"")) {
            throw new LexException("Unterminated string literal");
        }

        // emit the entire string as a token
        return new Token(Token.Type.STRING, chars.emit());
    }



    private Token lexCharacter() throws LexException {
        // ensure the character literal starts with a single quote
        if (!chars.match("'")) {
            throw new LexException("Character literal must start with a single quote");
        }

        // check if there is a character inside the quotes
        if (!chars.has(0)) {
            throw new LexException("Unexpected end of input in character literal");
        }

        // handle escape sequences or regular characters
        String character;
        if (chars.peek("\\\\")) {
            character=lexEscape();  //
        } else {
            character = chars.match(".") ? chars.emit() : "";  // Consume a single character
        }

        // ensure the character literal ends with a single quote
        if (!chars.match("'")) {
            throw new LexException("Character literal must end with a single quote");
        }

        // return the character as a token
        return new Token(Token.Type.CHARACTER, character+"'");
    }

    public Token lexOperator() throws LexException {
        // valid
        int index = chars.index;
        int length = chars.length;
        boolean doubleoperator = false;
        String literal = "";
        if (chars.match("[=<>!&|]") && chars.has(0) && chars.match("="))
        {   // two-character operators
            literal = chars.emit();
            // remove spaces from the literal
            literal = literal.replace(" ", "");
            doubleoperator = true;}
            else { chars.index = index;
            chars.length = length;}
             if (doubleoperator)
             {return new Token(Token.Type.OPERATOR,literal);}
        else if (chars.match("[=<>!&|+\\-*/();]")) {
            // emit token
            literal = chars.emit();
            // remove spaces from the literal
            literal = literal.replace(" ", "");
            return new Token(Token.Type.OPERATOR,literal);
        }
          else {        throw new LexException("Invalid operator: unrecognized symbol");}

    }


    /**
     * A helper class for maintaining the state of the character stream (input)
     * and methods for building up token literals.
     */
    private static final class CharStream {

        private final String input;
        int index = 0;
        int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is a regex matching only ONE character!
         */
        public boolean peek(String... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var character = input.charAt(index + offset);
                if (!String.valueOf(character).matches(patterns[offset])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the character stream.
         */
        public boolean match(String... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
                length += patterns.length;
            }
            return peek;
        }



        /**
         * Returns the literal built by all characters matched since the last
         * call to emit(); also resetting the length for subsequent tokens.
         */
        public String emit() {
            var literal = input.substring(index - length, index);
            length = 0;
            return literal;
        }

    }

}
