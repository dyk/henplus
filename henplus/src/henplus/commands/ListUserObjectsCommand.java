/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.HenPlus;
import henplus.Interruptable;
import henplus.SQLSession;
import henplus.SigIntHandler;
import henplus.view.util.NameCompleter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * FIXME: use SQLMetaData stuff instead.
 */
public class ListUserObjectsCommand 
    extends AbstractCommand implements Interruptable 
{
    final private static String[] LIST_TABLES = { "TABLE" };
    final private static String[] LIST_VIEWS  = { "VIEW" };
    final private static int[]    TABLE_DISP_COLS   = { 2, 3, 4, 5 };
    final private static int[]    PROC_DISP_COLS   = { 2, 3, 8 };
    
    /**
     * all tables in one session.
     */
    final private Map/*<SQLSession,SortedMap>*/  sessionTables;
    final private Map/*<SQLSession,SortedMap>*/  sessionColumns;
    final private HenPlus                        henplus;
    
    private boolean interrupted;
    
    public ListUserObjectsCommand(HenPlus hp) {
        sessionTables = new HashMap();
        sessionColumns = new HashMap();
        henplus = hp;
        interrupted = false;
    }

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "tables", "views", "procedures", "rehash"
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
                HenPlus.msg().println("catalog: " + catalog);
                ResultSetRenderer catalogrenderer = 
                    new ResultSetRenderer(meta.getSchemas(), "|", true, true,
                                          2000, HenPlus.out());
                catalogrenderer.execute();
                */
		ResultSetRenderer renderer;
                ResultSet rset;
                String objectType;
                int[] columnDef;
                if ("procedures".equals(cmd)) {
                    objectType = "Procecdures";
                    HenPlus.msg().println(objectType);
                    rset = meta.getProcedures(catalog, null, null);
                    columnDef = PROC_DISP_COLS;
                }
                else {
                    boolean showViews = "views".equals(cmd);
                    objectType = ((showViews) ? "Views" : "Tables");
                    HenPlus.msg().println(objectType);
                    rset = meta.getTables(catalog,
                                          null, null,
                                          (showViews)
                                          ? LIST_VIEWS
                                          : LIST_TABLES);
                    columnDef = TABLE_DISP_COLS;
                }
                
		renderer = new ResultSetRenderer(rset, "|", true, true, 2000,
                                                 HenPlus.out(),
						 columnDef);
                renderer.getDisplayMetaData()[2].setAutoWrap(78);

		int tables = renderer.execute();
		if (tables > 0) {
		    HenPlus.msg().println(tables + " " + objectType + " found.");
		    if (renderer.limitReached()) {
			HenPlus.msg().println("..and probably more; reached display limit");
		    }
		}
	    }
	    catch (Exception e) {
		HenPlus.msg().println(e.getMessage());
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
        /*
         * This may be a lengthy process..
         */
        interrupted = false;
        SigIntHandler.getInstance().pushInterruptable(this);
        NameCompleter tables = getTableCompleter(session);
        if (tables == null) return null;
        Iterator table = tables.getAllNamesIterator();
        compl = new NameCompleter();
        while (!interrupted && table.hasNext()) {
            String tabName = (String) table.next();
            Collection columns = columnsFor(tabName);
            Iterator cit = columns.iterator();
            while (cit.hasNext()) {
                String col = (String) cit.next();
                compl.addName(col);
            }
        }
        if (interrupted) {
            compl = null;
        }
        else {
            sessionColumns.put(session, compl);
        }
        SigIntHandler.getInstance().popInterruptable();
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
	SQLSession session = henplus.getCurrentSession();
        Set result = new HashSet();
	Connection conn = session.getConnection();  // use createStmt
	ResultSet rset = null;

        String schema = null;
        int schemaDelim = tabName.indexOf('.');
        if (schemaDelim > 0) {
            schema = tabName.substring(0, schemaDelim);
            tabName = tabName.substring(schemaDelim+1);
        }
	try {
	    DatabaseMetaData meta = conn.getMetaData();
	    rset = meta.getColumns(conn.getCatalog(), schema, tabName, null);
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
	Iterator it = completeTableName(HenPlus.getInstance().getCurrentSession(), tabName);	
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
    public Iterator completeTableName(SQLSession session, String partialTable) {
	if (session == null) return null;
	NameCompleter completer = getTableCompleter(session);
	return completer.getAlternatives(partialTable);
    }
    
    public Iterator completeAllColumns(String partialColumn) {
	SQLSession session = henplus.getCurrentSession();
        if (session == null) return null;
        NameCompleter completer = getAllColumnsCompleter(session);
        return completer.getAlternatives(partialColumn);
    }
    
    public Iterator getTableNamesIteratorForSession( SQLSession session ) {
        return getTableCompleter( session ).getAllNamesIterator();
    }
    
    public SortedSet getTableNamesForSession( SQLSession session ) {
        return getTableCompleter( session ).getAllNames();
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

    public void interrupt() {
        interrupted = true;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
