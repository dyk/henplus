/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: HenPlus.java,v 1.20 2002-02-10 09:35:28 hzeller Exp $
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.util.Properties;
import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap;
import java.util.Stack;

import java.io.File;
import java.io.IOException;
import java.io.EOFException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import henplus.commands.*;

import org.gnu.readline.Readline;
import org.gnu.readline.ReadlineLibrary;

public class HenPlus {
    public static final boolean verbose = false; // debug.
    private static final String EXIT_MSG   = "good bye.";
    private static final String HENPLUSDIR = ".henplus";
    private static final String PROMPT     = "Hen*Plus> ";

    private static final byte START           = 1;  // statement == start
    private static final byte STATEMENT       = 1;
    private static final byte START_COMMENT   = 3;
    private static final byte COMMENT         = 4;
    private static final byte PRE_END_COMMENT = 5;
    private static final byte START_ANSI      = 6;
    private static final byte ENDLINE_COMMENT = 7;
    private static final byte STRING          = 8;
    private static final byte SQLSTRING       = 9;
    private static final byte POTENTIAL_END_FOUND = 10;

    public static final byte LINE_EXECUTED   = 1;
    public static final byte LINE_EMPTY      = 2;
    public static final byte LINE_INCOMPLETE = 3;
    
    private static HenPlus instance = null; // singleton.
    
    private byte              _parseState;

    private CommandDispatcher dispatcher;
    private SQLSession        session;
    private Properties        properties;
    private boolean           terminated;
    private String            prompt;
    private String            emptyPrompt;
    private boolean           _alreadyShutDown;
    private StringBuffer      _commandBuffer;
    private SetCommand        _settingStore;
    private Stack             _inputBufferStack;
    private boolean           _fromTerminal;
    private BufferedReader    _fileReader;
    private boolean           _optQuiet;

    private HenPlus(Properties properties, String argv[]) throws IOException {
	terminated = false;
	this.properties = properties;
	_commandBuffer = new StringBuffer();
	_inputBufferStack = new Stack();
	_alreadyShutDown = false;
	
	try {
	    Readline.load(ReadlineLibrary.GnuReadline);
	    System.err.println("using GNU readline.");
	} catch (UnsatisfiedLinkError ignore_me) {
	    if (verbose) System.err.println(ignore_me);
	    System.err.println("no readline found. Using simple stdin.");
	}
	Readline.initReadline("HenPlus");
	try {
	    Readline.readHistoryFile(getHistoryLocation());
	}
	catch (Exception ignore) {}
	
	Readline.setWordBreakCharacters(" ");
	_fromTerminal = Readline.hasTerminal();
	if (!_fromTerminal) {
	    System.err.println("input not a terminal; disabling TAB-completion");
	}

	/*
	 * initialize known JDBC drivers.
	 */
	Enumeration props = properties.propertyNames();
	while (props.hasMoreElements()) {
	    String name = (String) props.nextElement();
	    if (name.startsWith("driver.") && name.endsWith(".class")) {
		try {
		    String driverClass = properties.getProperty(name);
		    if (verbose) System.err.print("loading .. '" + driverClass + "'");
		    Class.forName(driverClass);
		    if (verbose) System.err.println(" done.");
		}
		catch (Throwable t) {
		    if (verbose) System.err.println(" failed: " + t.getMessage());
		}
	    }
	}

	_settingStore = new SetCommand(this);
	dispatcher = new CommandDispatcher(_settingStore);
	dispatcher.register(new HelpCommand());
	dispatcher.register(new DescribeCommand());
	dispatcher.register(new SQLCommand());
	dispatcher.register(new ListUserObjectsCommand());
	dispatcher.register(new ExportCommand());
	dispatcher.register(new ImportCommand());
	dispatcher.register(new ShellCommand());
	dispatcher.register(new EchoCommand());
	dispatcher.register(new ExitCommand());
	dispatcher.register(new StatusCommand());
	dispatcher.register(new ConnectCommand( argv, this ));
	dispatcher.register(new LoadCommand());
	dispatcher.register(new AutocommitCommand()); // replace with 'set'
	dispatcher.register(_settingStore);
	Readline.setCompleter( dispatcher );
	setDefaultPrompt();
	// in case someone presses Ctrl-C
	try {
	    Runtime.getRuntime()
		.addShutdownHook(new Thread() {
			public void run() {
			    shutdown();
			}
		    });
	}
	catch (NoSuchMethodError e) {
	    // compiled with jdk >= 1.3, executed with <= 1.2.x
	    System.err.println("== This JDK is OLD. ==");
	    System.err.println(" - No final save on CTRL-C supported.");
	    System.err.println(" - and if your shell is broken after use of henplus: same reason.");
	    System.err.println("Bottomline: update your JDK (>= 1.3)!");
	}
    }
    
    public void pushBuffer() {
	_inputBufferStack.push(_commandBuffer);
	_commandBuffer = new StringBuffer();
	_parseState = START;  // FIXME: we have to push the state as well.
    }

    public void popBuffer() {
	_commandBuffer = (StringBuffer) _inputBufferStack.pop();
	_parseState = START;  // FIXME: we have to pop the state as well.
    }

    public void resetBuffer() {
	_commandBuffer.setLength(0);
	_parseState = START;
    }

    public String readlineFromFile() throws IOException {
	if (_fileReader == null) {
	    _fileReader = new BufferedReader(new InputStreamReader(System.in));
	}
	String line = _fileReader.readLine();
	if (line == null) {
	    throw new EOFException("EOF");
	}
	return (line.length() == 0) ? null : line;
    }

    /**
     * add a new line. returns true if the line completes a command.
     */
    public byte addLine(String line) {
	byte result = LINE_EMPTY;
	/*
	 * special oracle comment 'rem'ark
	 */
	int startWhite = 0;
	while (startWhite < line.length() 
	       && Character.isWhitespace(line.charAt(startWhite))) {
	    ++startWhite;
	}
	if (line.length() >= (3 + startWhite)
	    && (line.substring(startWhite,startWhite+3)
		.toUpperCase()
		.equals("REM"))) {
	    return LINE_EMPTY;
	}

	StringBuffer lineBuf = new StringBuffer(line);
	lineBuf.append('\n');
	while (lineBuf.length() > 0) {
	    parsePartialInput(lineBuf, _commandBuffer);
	    if (_parseState == POTENTIAL_END_FOUND) {
		//System.err.println(">'" + _commandBuffer.toString() + "'<");
		String completeCommand;
		completeCommand = varsubst(_commandBuffer.toString(),
					   _settingStore.getVariableMap());
		Command c = dispatcher.getCommandFrom(completeCommand);
		if (c == null) {
		    _parseState = START;
		    resetBuffer();
		    result = LINE_EMPTY;
		}
		else if(!c.isComplete(completeCommand)) {
		    _parseState = START;
		    result = LINE_INCOMPLETE;
		}
		else {
		    //System.err.println("SUBST: " + completeCommand);
		    dispatcher.execute(session, completeCommand);
		    resetBuffer();
		    result = LINE_EXECUTED;
		}
	    }
	    else {
		//System.err.println("#'" + _commandBuffer.toString() + "'#");
		result = LINE_INCOMPLETE;
	    }
	}
	return result;
    }

    public void run() {
	String cmdLine = null;
	String displayPrompt = prompt;
	resetBuffer();
	while (!terminated) {
	    try {
		cmdLine = (_fromTerminal)
		    ? Readline.readline( displayPrompt )
		    : readlineFromFile();
	    }
	    catch (EOFException e) {
		if (session != null) {
		    dispatcher.execute(session, "disconnect");
		    displayPrompt = prompt;
		    continue;
		}
		else {
		    break; // last session closed.
		}
	    }
	    catch (Exception e) { /* ignore */ }
	    if (cmdLine == null)
		continue;
	    if (addLine(cmdLine) == LINE_INCOMPLETE) {
		displayPrompt = emptyPrompt;
	    }
	    else {
		displayPrompt = prompt;
	    }
	}
    }
    
    private void shutdown() {
	if (_alreadyShutDown) {
	    return;
	}
	System.err.println("storing settings..");
	try {
	    if (dispatcher != null) {
		dispatcher.shutdown();
	    }
	    try {
		Readline.writeHistoryFile(getHistoryLocation());
	    }
	    catch (Exception ignore) {}
	    Readline.cleanup();
	}
	finally {
	    _alreadyShutDown = true;
	}
    }

    public void terminate() {
	terminated = true;
    }
    public CommandDispatcher getDispatcher() { return dispatcher; }
    
    public void setSession(SQLSession session) {
	this.session = session;
    }

    public SQLSession getSession() {
	return session;
    }

    public void setPrompt(String prompt) {
	this.prompt = prompt;
	StringBuffer tmp = new StringBuffer();
	for (int i=prompt.length(); i > 0; --i) {
	    tmp.append(' ');
	}
	emptyPrompt = tmp.toString();
    }
    
    public void setDefaultPrompt() {
	setPrompt( PROMPT );
    }

    /**
     * parse partial input and set state to POTENTIAL_END_FOUND if we
     * either reached end-of-line or a semicolon.
     */
    private void parsePartialInput (StringBuffer input, StringBuffer parsed) {
	int pos = 0;
	char current;
	byte oldstate = -1;
	byte state = _parseState; // local: faster access.
	
	while (state != POTENTIAL_END_FOUND && pos < input.length()) {
	    current = input.charAt(pos);
	    //System.out.print ("Pos: " + pos + "\t");
	    switch (state) {
	    case STATEMENT :
		if (current == '\n' || current == '\r')
		    state = POTENTIAL_END_FOUND;
		else if (current == ';')  state = POTENTIAL_END_FOUND;
		else if (current == '/')  state = START_COMMENT;
		else if (current == '"')  state = STRING;
		else if (current == '\'') state = SQLSTRING;
		else if (current == '-')  state = START_ANSI;
		break;
	    case START_COMMENT:
		if (current == '*')         state = COMMENT;
		/*
		 * Endline comment in the style '// comment' is not a
		 * good idea, since many JDBC-urls contain the '//' as
		 * part of the URL .. and this should _not_ be regarded as
		 * commend of course.
		 */
		//else if (current == '/')    state = ENDLINE_COMMENT;
		else { parsed.append ('/'); state = STATEMENT; }
		break;
	    case COMMENT:
		if (current == '*') state = PRE_END_COMMENT;
		break;
	    case PRE_END_COMMENT:
		if (current == '/')      state = STATEMENT;
		else if (current == '*') state = PRE_END_COMMENT;
		else state = COMMENT;
		break;
	    case START_ANSI:
		if (current == '-')        state = ENDLINE_COMMENT;
		else { parsed.append('-'); state = STATEMENT; }
		break;
	    case ENDLINE_COMMENT:
		if (current == '\n')      state = POTENTIAL_END_FOUND;
		else if (current == '\r') state = POTENTIAL_END_FOUND;
		break;
	    case STRING:     
		if (current == '"') state = STATEMENT;
		break;
	    case SQLSTRING:
		if (current == '\'') state = STATEMENT;
		break;
	    }
	    
	    /* append to parsed; ignore comments */
	    if ((state == STATEMENT && oldstate != PRE_END_COMMENT)
		|| state == STRING
		|| state == SQLSTRING
		|| state == POTENTIAL_END_FOUND) {
		parsed.append(current);
	    }
	    
	    oldstate = state;
	    pos++;
	}
	// we reached: POTENTIAL_END_FOUND. Store the rest in the input-buf
	StringBuffer rest = new StringBuffer();
	/* skip leading whitespaces of next statement .. */
	while (pos < input.length() 
	       && Character.isWhitespace (input.charAt(pos))) {
	    ++pos;
	}
	while (pos < input.length()) { 
	    rest.append(input.charAt(pos)); 
	    pos++; 
	}
	input.setLength(0);
	input.append(rest.toString()); // stuid jdk 1.2.2
	_parseState = state;
    }
    
    public String varsubst (String in, Map variables) {
        int pos             = 0;
        int endpos          = 0;
	int startVar        = 0;
        StringBuffer result = new StringBuffer();
        String      varname;
        boolean     hasBrace= false;
        boolean     knownVar= false;
        
        if (in == null) {
            return null;
        }
        
        if (variables == null) {
            return in;
        }
        
        while ((pos = in.indexOf ('$', pos)) >= 0) {
	    startVar = pos;
            if (in.charAt(pos+1) == '$') { // quoting '$'
                pos++;
                continue;
            }
            
            hasBrace = (in.charAt(pos+1) == '{');
            
            // text between last variable and here
            result.append(in.substring (endpos, pos));
            
            if (hasBrace) {
                pos++;
            }

            endpos = pos+1;
            while (endpos < in.length() 
                   && Character.isJavaIdentifierPart(in.charAt(endpos))) {
                endpos++;
            }
            varname=in.substring(pos+1,endpos);
         
            if (hasBrace) {
                while (endpos < in.length() && in.charAt(endpos) != '}') {
                    ++endpos;
                }
                ++endpos;
            }
	    if (endpos >= in.length()) {
		if (variables.containsKey(varname)) {
		    System.err.println("warning: missing '}' for variable '"
				       + varname + "'.");
		}
		result.append(in.substring(startVar));
		break;
	    }

            if (variables.containsKey(varname)) {
		result.append(variables.get(varname));
	    }
	    else {
		System.err.println("warning: variable '" 
				   + varname + "' not set.");
		result.append(in.substring(startVar, endpos));
	    }
   
            pos = endpos;
        }
	if (endpos < in.length()) {
	    result.append(in.substring(endpos));
	}
        return result.toString();
    }
    
    //*****************************************************************
    public static HenPlus getInstance() {
	return instance;
    }

    public static final void main(String argv[]) throws Exception {
	Properties properties = new Properties();
	
	properties.setProperty("driver.Oracle.class", 
			       "oracle.jdbc.driver.OracleDriver");
	properties.setProperty("driver.Oracle.example",
			       "jdbc:oracle:thin:@localhost:1521:ORCL");
	/*
	 * wenn auskommentieren, dann mal 'verbose' oben einstellen, denn
	 * es scheint, dass das nicht richtig geladen wird.
	 *
	properties.setProperty("driver.DB2.class",
			       "COM.ibm.db2.jdbc.net.DB2Driver");
	properties.setProperty("driver.DB2.example",
			       "jdbc:db2://localhost:6789/foobar");
	*/
	properties.setProperty("driver.MySQL.class",
			       "org.gjt.mm.mysql.Driver");
	properties.setProperty("driver.MySQL.example",
			       "jdbc:mysql://localhost/foobar");

	properties.setProperty("driver.SAP-DB.class",
			       "com.sap.dbtech.jdbc.DriverSapDB");
	properties.setProperty("driver.SAP-DB.example",
			       "jdbc:sapdb://localhost/foobar");

	properties.setProperty("driver.Postgres.class",
			       "org.postgresql.Driver");
	properties.setProperty("driver.Postgres.example",
			       "jdbc:postgresql://localhost/foobar");
	
	String cpy;
	cpy = 
"-------------------------------------------------------------------------\n"
+" HenPlus II 0.1 Copyright(C) 1997, 2001 Henner Zeller <H.Zeller@acm.org>\n"
+" HenPlus is provided AS IS and comes with ABSOLUTELY NO WARRANTY\n"
+" This is free software, and you are welcome to redistribute it under the\n"
+" conditions of the GNU Public License <http://www.gnu.org/>\n"
+"----------------------------------------------------[$Revision: 1.20 $]--\n";
	System.err.println(cpy);

	instance = new HenPlus(properties, argv);
	instance.run();
	instance.shutdown();
	System.err.println( EXIT_MSG );
    }

    public File getConfigDir() {
	/*
	 * test local directory.
	 */
	File henplusDir = new File( HENPLUSDIR );
	if (henplusDir.exists() && henplusDir.isDirectory()) {
	    return henplusDir;
	}

	/*
	 * fallback: home directory.
	 */
	String homeDir = System.getProperty("user.home", ".");
	henplusDir = new File(homeDir + File.separator + HENPLUSDIR);
	if (!henplusDir.exists()) {
	    henplusDir.mkdir();
	}
	return henplusDir;
    }

    private String getHistoryLocation() {
	return getConfigDir().getAbsolutePath() + File.separator + "history";
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
