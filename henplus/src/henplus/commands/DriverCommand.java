/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: DriverCommand.java,v 1.1 2002-02-15 00:02:27 hzeller Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Properties;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.StringTokenizer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;

import henplus.HenPlus;
import henplus.util.*;
import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.SQLSession;

/**
 * document me.
 */
public final class DriverCommand extends AbstractCommand {
    private final static boolean verbose = false; // debug.
    private final static String[][] KNOWN_DRIVERS = {
	{ "Oracle", "oracle.jdbc.driver.OracleDriver", 
	  "jdbc:oracle:thin:@localhost:1521:ORCL" },
	{ "DB2", "COM.ibm.db2.jdbc.net.DB2Driver",
	  "jdbc:db2://localhost:6789/foobar" },
	{ "Postgres", "org.postgresql.Driver",
	  "jdbc:postgresql://localhost/foobar" },
	{ "SAP-DB", "com.sap.dbtech.jdbc.DriverSapDB",
	  "jdbc:sapdb://localhost/foobar" },
	{ "MySQL", "org.gjt.mm.mysql.Driver", 
	  "jdbc:mysql://localhost/foobar" },
	{ "Adabas", "de.sag.jdbc.adabasd.ADriver",
	  "jdbc:adabasd://localhost:7200/work" }
    };

    private final static String DRIVERS_FILENAME = "drivers";
    private final static ColumnMetaData[] DRV_META;
    static {
	DRV_META = new ColumnMetaData[3];
	DRV_META[0] = new ColumnMetaData("for");
	DRV_META[1] = new ColumnMetaData("driver class");
	DRV_META[2] = new ColumnMetaData("sample url");
    }

    private final static class DriverDescription {
	private final String className;
	private final String sampleURL;
	public DriverDescription(String cn, String surl) {
	    className = cn;
	    sampleURL = surl;
	}
	public String getClassName() { return className; }
	public String getSampleURL() { return sampleURL; }
    }

    private final SortedMap/*<String,DriverDescription>*/ _drivers;
    private final HenPlus   _henplus;

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "list-drivers", "register", "unregister"
	};
    }
    
    public DriverCommand(HenPlus henplus) {
	_henplus = henplus;
	_drivers = new TreeMap();
	try {
	    File driversFile = new File(henplus.getConfigDir(),
					DRIVERS_FILENAME);
	    InputStream stream = new FileInputStream(driversFile);
	    Properties p = new Properties();
	    p.load(stream);
	    stream.close();
	    Enumeration props = p.propertyNames();
	    while (props.hasMoreElements()) {
		String name = (String) props.nextElement();
		if (name.startsWith("driver.") && name.endsWith(".class")) {
		    String databaseName = name.substring("driver.".length(),
							 name.length() - 
							 ".class".length());
		    String exampleName = "driver." + databaseName + ".example";
		    DriverDescription desc;
		    
		    desc = new DriverDescription(p.getProperty(name),
						 p.getProperty(exampleName));
		    _drivers.put(databaseName, desc);
		}
	    }
	}
	catch (IOException dont_care) {
	    for (int i=0; i < KNOWN_DRIVERS.length; ++i) {
		String[] row = KNOWN_DRIVERS[i];
		_drivers.put(row[0], new DriverDescription(row[1], row[2]));
	    }
	}
	loadDrivers();
    }

    private boolean loadDriver(String drvClass) {
	try {
	    if (verbose) System.err.print("loading .. '" + drvClass + "'");
	    Class.forName(drvClass);
	    if (verbose) System.err.println(" done.");
	    return true;
	}
	catch (Throwable t) {
	    if (verbose) System.err.println(" failed: " + t.getMessage());
	}
	return false;
    }

    private void loadDrivers() {
	Iterator it = _drivers.values().iterator();
	while (it.hasNext()) {
	    DriverDescription desc = (DriverDescription) it.next();
	    loadDriver(desc.getClassName());
	}
    }

    public boolean requiresValidSession(String cmd) { return false; }

    /**
     * execute the command given.
     */
    public int execute(SQLSession currentSession, String command) {
	StringTokenizer st = new StringTokenizer(command);
	String cmd = (String) st.nextElement();
	int argc = st.countTokens();
	
	if ("list-drivers".equals(cmd)) {
	    if (argc == 0) {
		DRV_META[0].reset();
		DRV_META[1].reset();
		DRV_META[2].reset();
		TableRenderer table = new TableRenderer(DRV_META, System.out);
		Iterator vars = _drivers.entrySet().iterator();
		while (vars.hasNext()) {
		    Map.Entry entry = (Map.Entry) vars.next();
		    Column[] row = new Column[3];
		    row[0] = new Column((String) entry.getKey());
		    DriverDescription desc=(DriverDescription)entry.getValue();
		    row[1] = new Column( desc.getClassName());
		    row[2] = new Column( desc.getSampleURL());
		    table.addRow(row);
		}
		table.closeTable();
		return SUCCESS;
	    }
	    else
		return SYNTAX_ERROR;
	}
	else if ("register".equals(cmd)) {
	    if (argc < 2 || argc > 3)
		return SYNTAX_ERROR;
	    String shortname   = (String) st.nextElement();
	    String driverClass = (String) st.nextElement();
	    String sampleURL   = null;
	    if (argc >= 3) {
		sampleURL = (String) st.nextElement();
	    }
	    if (!loadDriver(driverClass)) {
		System.err.println("cannot load driver class '" 
				   + driverClass + "'");
		return EXEC_FAILED;
	    }
	    else {
		_drivers.put(shortname, new DriverDescription(driverClass,
							      sampleURL));
	    }
	}
	else if ("unregister".equals(cmd)) {
	    if (argc != 1)
		return SYNTAX_ERROR;
	    String shortname   = (String) st.nextElement();
	    if (!_drivers.containsKey(shortname)) {
		System.err.println("unknown driver for '" + shortname + "'");
		return EXEC_FAILED;
	    }
	    else {
		_drivers.remove(shortname);
	    }
	}
	return SUCCESS;
    }
    
    public Iterator complete(CommandDispatcher disp,
			     String partialCommand, final String lastWord) 
    {
	StringTokenizer st = new StringTokenizer(partialCommand);
	String cmd = (String) st.nextElement();
	int argc = st.countTokens();
	// list-drivers gets no names.
	if ("list-drivers".equals(cmd)) 
	    return null;
	// do not complete beyond first word.
	if (argc > ("".equals(lastWord) ? 0 : 1)) {
		return null;
	}
	final Iterator it = _drivers.tailMap(lastWord).keySet().iterator();
	return new Iterator() {
		String var = null;
		public boolean hasNext() {
		    while (it.hasNext()) {
			var = (String) it.next();
			if (!var.startsWith(lastWord)) {
			    return false;
			}
			return true;
		    }
		    return false;
		}
		public Object  next() { return var; }
		public void remove() { 
		    throw new UnsupportedOperationException("no!");
		}
	    };
    }

    public void shutdown() {
	try {
	    File driversFile = new File(_henplus.getConfigDir(),
					DRIVERS_FILENAME);
	    OutputStream stream = new FileOutputStream(driversFile);
	    Properties p = new Properties();
	    Iterator drvs = _drivers.entrySet().iterator();
	    while (drvs.hasNext()) {
		Map.Entry entry = (Map.Entry) drvs.next();
		String shortName = (String) entry.getKey();
		DriverDescription desc=(DriverDescription)entry.getValue();
		p.put("driver." + shortName + ".class", desc.getClassName());
		p.put("driver." + shortName + ".example",desc.getSampleURL());
	    }
	    p.store(stream, "JDBC drivers");
	}
	catch (IOException dont_care) {}
    }

    /**
     * return a descriptive string.
     */
    public String getShortDescription() {
	return "handle JDBC drivers";
    }

    public String getSynopsis(String cmd) {
	if ("unregister".equals(cmd)) {
	    return cmd + " <shortname>";
	}
	else if ("register".equals(cmd)) {
	    return cmd + " <shortname> <driver-class> [sample-url]";
	}
	return cmd;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
