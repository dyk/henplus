/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.SQLSession;
import henplus.AbstractCommand;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/**
 * document me.
 */
public class ListUserObjectsCommand extends AbstractCommand {
    final String[] LIST_TABLES = { "TABLE" };
    final String[] LIST_VIEWS  = { "VIEW" };

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "tables", "views", "synonyms", "indices"
	};
    }

    /**
     * execute the command given.
     */
    public int execute(SQLSession session, String cmd) {
	try {
	    Connection conn = session.getConnection();  // use createStmt
	    DatabaseMetaData meta = conn.getMetaData();
	    String catalog = conn.getCatalog();
	    System.err.println("catalog: " + catalog);
	    ResultSetRenderer renderer = 
		new ResultSetRenderer(meta.getCatalogs(), System.out);
	    renderer.execute();
	    renderer = new ResultSetRenderer(meta.getTables(catalog,
							    null, null,
							    "views".equals(cmd)
							    ? LIST_VIEWS
							    : LIST_TABLES),
					     System.out);
	    renderer.execute();
	}
	catch (Exception e) {
	    System.err.println(e.getMessage());
	    return EXEC_FAILED;
	}
	return SUCCESS;
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
	dsc="\tLists all " + cmd + " available in this schema.";
	return dsc;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
