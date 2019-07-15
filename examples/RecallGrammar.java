import norswap.autumn.DSL;
import norswap.autumn.Parse;
import norswap.autumn.ParseState;
import norswap.autumn.parsers.AbstractForwarding;
import norswap.autumn.parsers.AbstractPrimitive;
import java.util.HashMap;
import java.util.Map;

/**
 * See /doc/B2-context-sensitive-parsing.md for a full explanation of this example.
 */
public final class RecallGrammar extends DSL
{
    ParseState<Map<String, String>> store = new ParseState<>(RecallGrammar.class, HashMap::new);

    final class Learn extends AbstractForwarding
    {
        public Learn (String key, rule child)
        {
            super("learn", child.collect().action_with_string(
                (p,xs,str) -> p.log.apply(() -> {
                    Map<String, String> map = store.data(p);
                    String old = map.get(key);
                    map.put(key, str);
                    return () -> {
                        if (old != null) map.put(key, old);
                        else map.remove(key);
                    };
                })).get());
        }
    }

    final class Recall extends AbstractPrimitive
    {
        private final String key;

        public Recall (String key) {
            super("recall", false);
            this.key = key;
        }

        @Override protected boolean doparse (Parse parse)
        {
            String string = store.data(parse).get(key);
            if (string == null)
                throw new IllegalStateException("No registered string for key: " + key);
            if (parse.match(parse.pos, string)) {
                parse.pos += string.length();
                return true;
            }
            return false;
        }
    }

    public rule identifier = alpha.at_least(1);
    public rule learn_id = rule(new Learn("id", identifier));
    public rule recall_id = rule(new Recall("id"));
    public rule root = seq(learn_id, str("-"), recall_id);
}
