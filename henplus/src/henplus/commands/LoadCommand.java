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
import java.util.Set;
import java.util.Stack;
import java.util.HashSet;
import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * document me.
 */
public class LoadCommand extends AbstractCommand {
    /**
     * to determine recursively loaded files, we remember all open files.
     */
    private final Set/*<File>*/   _openFiles;

    /**
     *  current working directory stack - to always open files relative to
     * the currently open file.
     */
    private final Stack/*<File>*/ _cwdStack;

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "load", "start", "@", "@@"
	};
    }

    public LoadCommand() {
	_openFiles = new HashSet();
	_cwdStack = new Stack();
	try {
	    File cwd = new File(".");
	    _cwdStack.push(cwd.getCanonicalFile());
	}
	catch (IOException e) {
	    System.err.println("cannot determine current working directory: " 
			       + e);
	}
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
	    File currentFile = null;
	    try {
		henplus.pushBuffer();
		henplus.getDispatcher().startBatch();
		File f = new File(filename);
		if (!f.isAbsolute()) {
		    f = new File((File)_cwdStack.peek(), filename);
		}
		f = f.getCanonicalFile();
		if (_openFiles.contains(f)) {
		    throw new IOException("recursive inclusion alert: skipping file " + f.getName());
		}
		System.err.println(f.getName());
		currentFile = f;
		_openFiles.add(currentFile);
		_cwdStack.push(currentFile.getParentFile());
		BufferedReader reader = new BufferedReader(new FileReader(currentFile));
		String line;
		while ((line = reader.readLine()) != null) {
		    if (henplus.executeLine(line) == HenPlus.LINE_EXECUTED) {
			++commandCount;
		    }
		}
	    }
	    catch (Exception e) {
		//e.printStackTrace();
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
		if (currentFile != null) {
		    _openFiles.remove(currentFile);
		    _cwdStack.pop();
		}
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
	    System.err.println(" (" + filename + ")");
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
	return "\tOpens one file or a sequence of files and reads the\n"
	    +  "\tcontained sql-commands line by line. If the path of the\n"
	    +  "\tfilename is not absolute, it is interpreted relative to\n"
	    +  "\tthe current working directory. If the load command itself\n"
	    +  "\tis executed in some loaded file, then the current working\n"
	    +  "\tdirectory is the directory that file is in.\n"
	    +  "\tThe commands 'load' and 'start' do exactly the same;\n"
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
