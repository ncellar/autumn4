package norswap.autumn.parsers;

import norswap.autumn.Parse;
import norswap.autumn.Parser;
import norswap.autumn.ParserVisitor;
import norswap.autumn.StackAction;
import java.util.Arrays;

/**
 * Matches a left-associative binary expression. See {@link #LeftAssoc}.
 */
public final class LeftAssoc extends Parser
{
    // ---------------------------------------------------------------------------------------------

    public final Parser left;

    // ---------------------------------------------------------------------------------------------

    public final Parser right;

    // ---------------------------------------------------------------------------------------------

    public final Parser operator;

    // ---------------------------------------------------------------------------------------------

    public final boolean operator_required;

    // ---------------------------------------------------------------------------------------------

    public final StackAction step;

    // ---------------------------------------------------------------------------------------------

    /**
     * Matches a left-associative binary expression (specified by {@code left}, {@code right} and
     * {@code operator}).
     *
     * @param operator_required specifies whether at least one operator should
     * be present or if a left-hand side alone is admissible.
     *
     * @param step is applied immediately after a right-hand side has been matched, enabling
     * left-associative tree building. If it is null, no action is taken.
     */
    public LeftAssoc (Parser left, Parser operator, Parser right,
                      boolean operator_required, StackAction step)
    {
        this.left = left;
        this.operator = operator;
        this.right = right;
        this.operator_required = operator_required;
        this.step = step;
    }

    // ---------------------------------------------------------------------------------------------

    @Override public boolean doparse (Parse parse)
    {
        int pos0  = parse.pos;
        int size0 = parse.stack.size();
        int count = 0;

        if (!left.parse(parse))
            return false;

        while (operator.parse(parse) && right.parse(parse))
        {
            ++ count;
            if (step != null)
                step.apply(parse, parse.pop_from(size0), pos0, size0);
        }

        return count > 0 || !operator_required;
    }

    // ---------------------------------------------------------------------------------------------

    @Override public void accept (ParserVisitor visitor) {
        visitor.visit(this);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Order: left, operator, right.
     */
    @Override public Iterable<Parser> children() {
        return Arrays.asList(left, operator, right);
    }

    // ---------------------------------------------------------------------------------------------

    @Override public String toStringFull()
    {
        StringBuilder b = new StringBuilder();
        b.append("left_assoc(");
        b.append(left)      .append(", ");
        b.append(operator)  .append(", ");
        b.append(right);
        if (operator_required)
            b.append(", operator_required");
        b.append(")");
        return b.toString();
    }

    // ---------------------------------------------------------------------------------------------
}
