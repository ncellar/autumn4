package norswap.autumn.parsers;

import norswap.autumn.DSL;
import norswap.autumn.Parse;
import norswap.autumn.Parser;
import norswap.autumn.SideEffect;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * This is a factory to generate {@link TokenParser}s.
 *
 * <p>The point is to create a set of parsers that are (a) mutually exclusive (only one can succeed
 * at any given input position) and (b) parsed efficiently by maintaining a cache of input position
 * to results. The idea being that many parsers in the set will be called at the same position.
 *
 * <p>This is most often useful for tokenization (lexical analysis) hence the name, but is
 * applicable to any other scenario that fits the same conditions.
 *
 * <p>To obtain the set of mutually exclusive parsers, we start by a set of base parsers which are
 * passed to the constructor of this class. These parsers do not need to be mutually exclusive - the
 * correct parser at each input position will be determined via longest-match (as with the {@link
 * Longest} parser).
 *
 * <p>To obtain the (mutually exclusive) token parsers, use the {@link #token_parser(Parser)}
 * method, passing it one of the base parsers. You can also use {@link #token_choice(Parser...)}
 * to obtain an optimized choice between token parsers.
 *
 * <p>The instance of this class maintains a cache to input positions to result (including the
 * matching parser, if any, the end position of the match and its side effects). Token parsers call
 * back into the instance in order to find if the token at the current position is the one they are
 * supposed to recognize. If the token at the current position is yet unknown, it is determined and
 * the cache is filled.
 *
 * <p>As such, an instance of this class — as well as the parsers it generates — is tied to a
 * specific {@link Parse}, unlike other standard parsers. It is however possible to reuse the
 * instance for a new parse, by calling the {@link #flush()} method.
 */
@SuppressWarnings("unchecked")
public final class Tokens
{
    // ---------------------------------------------------------------------------------------------

    /**
     * The array of base parsers used to parse tokens. You should not modify this, it is only
     * public for the sake for {@link DSL.Wrapper#token}.
     */
    public Parser[] parsers;

    // ---------------------------------------------------------------------------------------------

    private static final class Result
    {
        /** Marker that indicates a token couldn't be found at the corresponding position. */
        static final Result NONE = new Result(-1, -1, null);

        /** The parser that generated this result. */
        final int parser;

        /** The end position of the token. */
        final int end_position;

        /** List of side-effects generated by parsing the token. */
        final List<SideEffect> delta;

        private Result (int parser, int end_position, List<SideEffect> delta)
        {
            this.parser = parser;
            this.end_position = end_position;
            this.delta = delta;
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Max displacement from initial position in the cache. */
    private long max_displacement = 0;

    /** Amount of cache slots occupied. */
    private int occupied = 0;

    /** A map for positions. The index at which a position is stored is linked to the result at the
     * same index in {@link #results}. */
    private long[] cache = new long[1024];

    /** cf. {@link #cache} */
    private Result[] results = new Result[1024];

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns an unmodifiable list of the parsers used to parse tokens.
     */
    public List<Parser> parsers()
    {
        return Collections.unmodifiableList(Arrays.asList(parsers));
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Flushes the cache, allowing the object to be reused for a new parse.
     */
    public void flush()
    {
        max_displacement = 0;
        occupied = 0;
        cache = new long[1024];
        results = new Result[1024];
    }

    // ---------------------------------------------------------------------------------------------

    public Tokens (Parser... parsers)
    {
        this.parsers = parsers;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a {@link TokenParser} wrapping the given parser, which must be one of the parsers
     * that was passed to the constructor.
     */
    public TokenParser token_parser (Parser base_parser)
    {
        for (int i = 0; i < parsers.length; ++i)
            if (parsers[i] == base_parser)
                return new TokenParser(this, i);

        throw new Error("Parser " + base_parser + " is not a recognized base token parser.");
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a {@link TokenChoice} wrapping the given parsers, which must be among the parsers
     * that were passed to the constructor.
     */
    public TokenChoice token_choice (Parser... base_parsers)
    {
        int[] targets = new int[base_parsers.length];

        outer: for (int j = 0; j < base_parsers.length; ++j)
        {
            for (int i = 0; i < parsers.length; ++i)
                if (parsers[i] == base_parsers[j]) {
                    targets[j] = i;
                    continue outer;
                }

            throw new Error("Parser " + base_parsers[j] + " is not a recognized base token parser.");
        }

        return new TokenChoice(this, targets);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Insert the given (position, result) pair in the cache, under the assumption that the cache is
     * large enough. Does not update {@link #occupied}.
     */
    private void insert (int pos, Result res)
    {
        int i = pos % cache.length;
        long displacement = 0;

        while ((int) cache[i] != 0)
        {
            long d = cache[i] >>> 32;

            if (d <= displacement)
            {
                int pos2 = (int) cache[i] - 1;
                Result res2 = results[i];

                cache[i] = pos + 1;
                results[i] = res;

                if (displacement > max_displacement)
                    max_displacement = displacement;

                pos = pos2;
                res = res2;
                displacement = d;
            }

            ++displacement;
            if (++i == cache.length)
                i = 0;
        }

        if (displacement > max_displacement)
            max_displacement = displacement;

        cache[i] = pos + 1;
        results[i] = res;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Inserts the given (pos, result) pair into the cache.
     */
    private void cache (int pos, Result res)
    {
        insert(pos, res);

        if (++occupied / (double) cache.length > 0.8)
        {
            long[] old_cache = cache;
            Result[] old_results = results;

            cache   = new long   [cache.length * 2];
            results = new Result [cache.length * 2];

            for (int j = 0; j < old_cache.length; ++j)
                if ((int) old_cache[j] != 0)
                    insert((int) old_cache[j] - 1, old_results[j]);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Retrieve a result from the cache, null if the position is not in the cache, and {@link
     * Result#NONE} if the the position is in the cache, but no tokens matched at that position.
     */
    private Result get (int pos)
    {
        int i = pos % cache.length;
        int p = (int) cache[i]; // position
        int d = 0; // displacement

        while (p != pos && p != 0 && d <= max_displacement) {
            if (++i == cache.length) i = 0;
            p = (int) cache[i];
            ++d;
        }

        // TODO not a proper fix
        return p - 1 == pos ? results[i] : null;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Fills the cache with the result for the current position, and return the inserted result
     * (potentially {@link Result#NONE}).
     *
     * <p>Assumes no result for that position exist yet.
     */
    private Result fill_cache (Parse parse)
    {
        int pos0 = parse.pos;
        int log0 = parse.log.size();

        int longest = -1;
        int max_pos = pos0;
        List<SideEffect> delta = null;

        for (int i = 0; i < parsers.length; ++i)
        {
            boolean success = parsers[i].parse(parse);

            if (success) {
                if (parse.pos > max_pos) {
                    max_pos = parse.pos;
                    delta = parse.delta(log0);
                    longest = i;
                }

                parse.pos = pos0;
                parse.rollback(log0);
            }
        }

        Result result = delta == null
            ? Result.NONE
            : new Result(longest, max_pos, delta);

        cache(pos0, result);
        return result;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Tries to parse the token corresponding to the parser at the given {@code target} index,
     * returning true iff successful.
     *
     * <p>In all cases, fills the cache with the tokenization result for the current position.
     */
    boolean parse_token (Parse parse, int target)
    {
        if (parsers.length == 0)
            throw new Error(
                "No base token parsers. You probably failed to call DSL#build_tokenizer().");

        Result res = get(parse.pos);

        if (res == Result.NONE) // no token
            return false;

        if (res == null) // token for position not in cache yet
            res = fill_cache(parse);

        if (res.parser != target) // no token or wrong token
            return false;

        // correct token!
        parse.pos = res.end_position;
        res.delta.forEach(parse::apply);
        return true;
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Tries to parse one of the token corresponding to the parsers at the given {@code targets}
     * indices, returning true iff successful.
     *
     * <p>In all cases, fills the cache with the tokenization result for the current position.
     */
    boolean parse_token_choice (Parse parse, int[] targets)
    {
        if (parsers.length == 0)
            throw new Error(
                "No base token parsers. You probably failed to call DSL#build_tokenizer().");

        Result res = get(parse.pos);

        if (res == Result.NONE) // no token
            return false;

        if (res == null) // token for position not in cache yet
            res = fill_cache(parse);

        for (int target: targets)
            if (res.parser == target) { // a correct token
                parse.pos = res.end_position;
                res.delta.forEach(parse::apply);
                return true;
            }

        return false;
    }

    // ---------------------------------------------------------------------------------------------

    @Override public String toString()
    {
        return ""; // TODO
    }

    // ---------------------------------------------------------------------------------------------
}
