/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.SQLSession;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;

import java.io.PrintStream;

/*
 * foo
 * |-- bar
 * |   |-- blah
 * |   `-- (foo)            <-- cylic reference
 * `-- baz
 */

/**
 * creates a dependency graph.
 */
public class TreeCommand extends AbstractCommand {
    static final boolean verbose     = false;
    private final ListUserObjectsCommand tableCompleter;

    /**
     * A node in a cyclic graph.
     */
    private static abstract class Node implements Comparable {
        private final Set/*<Node>*/ _children;

        protected Node() {
            _children = new TreeSet();
        }
        
        public Node add(Node n) {
            _children.add(n);
            return n;
        }

        public void print(PrintStream out) {
            print(new TreeSet(), new StringBuffer(), "", out);
        }

        private void print(SortedSet alreadyPrinted,
                           StringBuffer currentIndent,
                           String indentString,
                           PrintStream out) 
        {
            if (indentString.length() > 0) { // otherwise we are toplevel.
                out.print("-- ");
            }
            String name = getName();
            boolean cyclic = alreadyPrinted.contains(name);
            //Terminal.blue(out);
            if (cyclic) out.print("(");
            out.print(name);
            if (cyclic) out.print(")");
            //Terminal.reset(out);
            out.println();
            if (cyclic) {
                return;
            }
            alreadyPrinted.add(name);
            int remaining = _children.size();
            if (remaining > 0) {
                int previousLength = currentIndent.length();
                currentIndent.append(indentString);
                Iterator it = _children.iterator();
                while (it.hasNext()) {
                    Node n = (Node) it.next();
                    out.print(currentIndent);
                    out.print((remaining == 1) ? '`' : '|');
                    n.print(alreadyPrinted, currentIndent,
                            (remaining == 1) ? "    " : "|   ", out);
                    --remaining;
                }
                currentIndent.setLength(previousLength);
            }
        }

        public int compareTo(Object o) {
            Node other = (Node) o;
            return getName().compareTo(other.getName());
        }

        /**
         * This is what we need to print the stuff..
         */
        public abstract String getName();
    }

    /**
     * the entity is simply represented as String.
     */
    private static class StringNode extends Node {
        private final String _name;
        public StringNode(String s) { _name = s; }
        public String getName() { return _name; }
    }


    public TreeCommand(ListUserObjectsCommand tc) {
	tableCompleter = tc;
    }

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] { "tree-view" }; 
    }

    /**
     * execute the command given.
     */
    public int execute(SQLSession session, String cmd, String param) {
	final StringTokenizer st = new StringTokenizer(param);
	final int argc = st.countTokens();
	if (argc != 1) {
	    return SYNTAX_ERROR;
	}

        boolean correctName = true;
        String tabName = (String) st.nextElement();
        if (tabName.startsWith("\"")) {
            tabName = stripQuotes(tabName);
            correctName = false;
        }
        if (correctName) {
            String alternative = tableCompleter.correctTableName(tabName);
            if (alternative != null && !alternative.equals(tabName)) {
                tabName = alternative;
            }
        }

        try {
            long startTime = System.currentTimeMillis();
            DatabaseMetaData dbMeta = session.getConnection().getMetaData();
            Node tree = buildTree(dbMeta, new TreeMap(), tabName);
            tree.print(HenPlus.out());
            TimeRenderer.printTime(System.currentTimeMillis()-startTime,
                                   HenPlus.msg());
            HenPlus.msg().println();
        }
        catch (Exception e) {
            HenPlus.msg().println("problem getting database meta data: " 
                                  + e.getMessage());
            return EXEC_FAILED;
        }
        return SUCCESS;
    }
    
    /**
     * build a subtree from the MetaData for the table with the given name.
     * If this node already exists (because of a cyclic dependency), 
     * return that. recursively called to build the whole tree.
     */
    private Node buildTree(DatabaseMetaData meta,
                           Map knownNodes, String tabName) 
        throws SQLException
    {
        if (knownNodes.containsKey(tabName)) {
            return (Node) knownNodes.get(tabName);
        }
        
        Node n = new StringNode(tabName);
        knownNodes.put(tabName, n);
        ResultSet rset = null;
        try {
            rset = meta.getExportedKeys(null, null, tabName);
            while (rset.next()) {
                String referencingTable = rset.getString(7);
                n.add(buildTree(meta, knownNodes, referencingTable));
            }
        }
        finally {
            if (rset != null) {
                try { rset.close(); } catch (Exception e) {}
            }
        }
        return n;
    }

    /**
     * complete the table name.
     */
    public Iterator complete(CommandDispatcher disp,
			     String partialCommand, String lastWord) 
    {
	StringTokenizer st = new StringTokenizer(partialCommand);
	String cmd = (String) st.nextElement();
	int argc = st.countTokens();
	// we accept only one argument.
	if (lastWord.startsWith("\"")) {
	    lastWord = lastWord.substring(1);
	}
	return tableCompleter.completeTableName(HenPlus.getInstance().getCurrentSession(), lastWord);
    }

    private String stripQuotes(String value) {
	if (value.startsWith("\"") && value.endsWith("\"")) {
	    value = value.substring(1, value.length()-1);
	}
	return value;
    }
    
    /**
     * return a descriptive string.
     */
    public String  getShortDescription() { 
	return "tree representation of connected tables";
    }
    
    public String getSynopsis(String cmd) {
	return cmd + " <tablename>";
    }
    
    public String getLongDescription(String cmd) {
	String dsc = null;
        dsc= "\tShow tables, that are connected via foreign keys in a\n"
            +"\ttree like manner. This is very helpful in exploring\n"
            +"\tcomplicated data structures or simply check if all\n"
            +"\tforeign keys are applied. This command works of course\n"
            +"\tonly with databases that support foreign keys (so _not_\n"
            +"\tMySQL). Invoke on the toplevel table you are interested in\n"
            +"\tExample:\n"
            +"\tConsider tables 'bar' and 'baz' that have a foreign key\n"
            +"\ton the table 'foo'. Further a table 'blah', that references\n"
            +"\t'bar'. The table 'foo' in turn references 'bar', thus\n"
            +"\tcyclicly referencing itself. Invoking tree-view on 'foo'\n"
            +"\twould be represented as\n"
            +"\t    foo\n"
            +"\t    |-- bar\n"
            +"\t    |   |-- blah\n"
            +"\t    |   `-- (foo)            <-- cylic reference\n"
            +"\t    `-- baz\n"
            +"\tSo in order to limit the potential cyclic graph in the\n"
            +"\ttree view from infinite to finite, cyclic nodes are shown\n"
            +"\tin parenthesis.";
        return dsc;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
