/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.HenPlus;
import henplus.SQLSession;
import henplus.AbstractCommand;
import henplus.CommandDispatcher;

import java.util.StringTokenizer;
import java.util.Iterator;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * document me.
 */
public class LoadCommand extends AbstractCommand {
    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "load", "start", "@", "@@"
	};
    }
    
    /**
     * filename completion by default.
     */
    public Iterator complete(CommandDispatcher disp, String partialCommand, 
			     String lastWord) {
	return new FileCompletionIterator(lastWord);
    }

    /**
     * execute the command given.
     */
    public int execute(SQLSession session, String cmd, String param) {
	StringTokenizer st = new StringTokenizer(param);
	int argc = st.countTokens();
	if (argc < 1) {
	    return SYNTAX_ERROR;
	}
	HenPlus henplus = HenPlus.getInstance();
	while (st.hasMoreElements()) {
	    int  commandCount = 0;
	    String filename = (String) st.nextElement();
	    long startTime = System.currentTimeMillis();
	    try {
		henplus.pushBuffer();
		henplus.getDispatcher().startBatch();
		File f = new File(filename);
		System.err.println(f.getName());
		BufferedReader reader = new BufferedReader(new FileReader(f));
		String line;
		while ((line = reader.readLine()) != null) {
		    if (henplus.addLine(line) == HenPlus.LINE_EXECUTED) {
			++commandCount;
		    }
		}
	    }
	    catch (Exception e) {
		System.err.println(e.getMessage());
		if (st.hasMoreElements()) {
		    System.err.println("..skipping to next file.");
		    continue;
		}
		return EXEC_FAILED;
	    }
	    finally {
		henplus.popBuffer(); // no open state ..
		henplus.getDispatcher().endBatch();
	    }
	    long execTime = System.currentTimeMillis() - startTime;
	    System.err.print(commandCount + " commands in ");
	    TimeRenderer.printTime(execTime, System.err);
	    if (commandCount != 0) {
		System.err.print("; avg. time ");
		TimeRenderer.printTime(execTime / commandCount, System.err);
	    }
	    if (execTime != 0 && commandCount > 0) {
		System.err.print("; " + 
				 (1000 * commandCount / execTime) 
				 + " per second");
	    }
	    System.err.println();
	}
	return SUCCESS;
    }

    public boolean requiresValidSession(String cmd) { return false; }

    /**
     * return a descriptive string.
     */
    public String getShortDescription() {
	return "load file and execute commands";
    }

    public String getSynopsis(String cmd) {
	return cmd + " <filename> [<filename> ..]";
    }

    public String getLongDescription(String cmd) {
	return "\topens one file or a sequence of files and and reads the\n"
	    +  "\tcontained sql-commands line by line.\n"
	    +  "\tThe commands 'load' and 'start' do exaclty the same;\n"
	    +  "\t'start', '@' and '@@' are provided for compatibility \n"
	    +  "\twith oracle SQLPLUS scripts. However, there is no\n"
	    +  "\tdistinction between '@' and '@@' as in SQLPLUS; henplus\n"
	    +  "\talways reads subfiles relative to the contained file.\n";
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
