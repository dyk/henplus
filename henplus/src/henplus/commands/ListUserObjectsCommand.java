/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import java.util.Map;
import java.util.HashMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Iterator;
import java.util.Collection;
import java.util.Vector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.ResultSet;

import henplus.HenPlus;
import henplus.SQLSession;
import henplus.AbstractCommand;

/**
 * document me.
 */
public class ListUserObjectsCommand extends AbstractCommand {
    final private static String[] LIST_TABLES = { "TABLE" };
    final private static String[] LIST_VIEWS  = { "VIEW" };
    final private static int[]    DISP_COLS   = { 2, 3, 5 };

    /**
     * all tables in one session.
     */
    final private Map/*<SQLSession,SortedMap>*/  sessionTables;
    final private Map/*<SQLSession,SortedMap>*/  sessionColumns;
    final private HenPlus                        henplus;

    public ListUserObjectsCommand(HenPlus hp) {
	sessionTables = new HashMap();
        sessionColumns = new HashMap();
	henplus = hp;
    }

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "tables", "views", "rehash"
	};
    }

    /**
     * execute the command given.
     */
    public int execute(SQLSession session, String cmd, String param) {
	if (cmd.equals("rehash")) {
	    rehash(session);
	}
	else {
	    try {
		Connection conn = session.getConnection();  // use createStmt
		DatabaseMetaData meta = conn.getMetaData();
		String catalog = conn.getCatalog();
		/*
                  System.err.println("catalog: " + catalog);
                  ResultSetRenderer renderer = 
                  new ResultSetRenderer(meta.getCatalogs(), System.out);
                  renderer.execute();
		*/
		ResultSetRenderer renderer;
		boolean showViews = "views".equals(cmd);
		String objectType = ((showViews) ? "Views" : "Tables");
		System.err.println(objectType);
		ResultSet rset = meta.getTables(catalog,
						null, null,
						(showViews)
						? LIST_VIEWS
						: LIST_TABLES);
		renderer = new ResultSetRenderer(rset, System.out,
						 DISP_COLS);
		int tables = renderer.execute();
		if (tables > 0) {
		    System.err.println(tables + " " + objectType + " found.");
		    if (renderer.limitReached()) {
			System.err.println("..and probably more; reached display limit");
		    }
		}
	    }
	    catch (Exception e) {
		System.err.println(e.getMessage());
		return EXEC_FAILED;
	    }
	}
	return SUCCESS;
    }

    private NameCompleter getTableCompleter(SQLSession session) {
	NameCompleter compl = (NameCompleter) sessionTables.get(session);
	return (compl == null) ? rehash(session) : compl;
    }

    private NameCompleter getAllColumnsCompleter(SQLSession session) {
        NameCompleter compl = (NameCompleter) sessionColumns.get(session);
        if (compl != null) {
            return compl;
        }
        NameCompleter tables = getTableCompleter(session);
        if (tables == null) return null;
        Iterator table = tables.getAllNames();
        compl = new NameCompleter();
        while (table.hasNext()) {
            String tabName = (String) table.next();
            Collection columns = columnsFor(tabName);
            Iterator cit = columns.iterator();
            while (cit.hasNext()) {
                String col = (String) cit.next();
                compl.addName(col);
            }
        }
        sessionColumns.put(session, compl);
        return compl;
    }

    public void unhash(SQLSession session) {
	sessionTables.remove(session);
    }

    /**
     * rehash table names.
     */
    private NameCompleter rehash(SQLSession session) {
	NameCompleter result = new NameCompleter();
	Connection conn = session.getConnection();  // use createStmt
	ResultSet rset = null;
	try {
	    DatabaseMetaData meta = conn.getMetaData();
	    rset = meta.getTables(null, null, null, LIST_TABLES);
	    while (rset.next()) {
		result.addName(rset.getString(3));
	    }
	}
	catch (Exception e) {
	    // ignore.
	}
	finally {
	    if (rset != null) {
		try { rset.close(); } catch (Exception e) {}
	    }
	}
	sessionTables.put(session, result);
        sessionColumns.remove(session);
	return result;
    }

    /**
     * fixme: add this to the cached values determined by rehash.
     */
    public Collection columnsFor(String tabName) {
	SQLSession session = henplus.getSession();
	Vector result = new Vector();
	Connection conn = session.getConnection();  // use createStmt
	ResultSet rset = null;
	try {
	    DatabaseMetaData meta = conn.getMetaData();
	    rset = meta.getColumns(null, null, tabName, null);
	    while (rset.next()) {
		result.add(rset.getString(4));
	    }
	}
	catch (Exception e) {
	    // ignore.
	}
	finally {
	    if (rset != null) {
		try { rset.close(); } catch (Exception e) {}
	    }
	}
	return result;
    }

    /**
     * see, if we find exactly one alternative, that is spelled
     * correctly. If we have more than one alternative but one, that
     * has the same length of the requested tablename, return this.
     */
    public String correctTableName(String tabName) {
	Iterator it = completeTableName(tabName);	
	if (it == null) return null;
	boolean foundSameLengthMatch = false;
	int count = 0;
	String correctedName = null;
	if (it.hasNext()) {
	    String alternative = (String) it.next();
	    boolean sameLength = (alternative != null
				  && alternative.length() == tabName.length());
	    
	    foundSameLengthMatch |= sameLength;
	    ++count;
	    if (correctedName == null || sameLength) {
		correctedName = alternative;
	    }
	}
	return (count == 1 || foundSameLengthMatch) ? correctedName : null;
    }

    /**
     * used from diverse commands that need table name completion.
     */
    public Iterator completeTableName(String partialTable) {
	SQLSession session = henplus.getSession();
	if (session == null) return null;
	NameCompleter completer = getTableCompleter(session);
	return completer.getAlternatives(partialTable);
    }
    
    public Iterator completeAllColumns(String partialColumn) {
	SQLSession session = henplus.getSession();
        if (session == null) return null;
        NameCompleter completer = getAllColumnsCompleter(session);
        return completer.getAlternatives(partialColumn);
    }

    /**
     * return a descriptive string.
     */
    public String  getShortDescription() { 
	return "list available user objects";
    }
    
    public String getSynopsis(String cmd) {
	return cmd;
    }

    public String getLongDescription(String cmd) {
	String dsc;
	if (cmd.equals("rehash")) {
	    dsc="\trebuild the internal hash for tablename completion.";
	}
	else {
	    dsc="\tLists all " + cmd + " available in this schema.";
	}
	return dsc;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
